package com.codeexplain.ai;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.codeexplain.common.ApiException;
import com.codeexplain.common.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 用户 API Key 的加解密（AES-256-GCM）。
 *
 * <p>为什么必须加密：库里存的是别人的付费凭据。一旦数据库备份、只读账号、误传的日志
 * 泄露了明文 Key，攻击者能直接花用户的钱，而且用户很难察觉。
 *
 * <p>为什么选 GCM 而不是 CBC：CBC 只保密不防篡改，密文被改动后解密仍会「成功」返回
 * 一段垃圾；GCM 自带认证标签，篡改一定解密失败。这里不需要自己拼 HMAC。
 *
 * <p>存储格式：{@code [12 字节 IV][密文+16 字节 tag]}。IV 每次随机，绝不复用——
 * GCM 下 IV 复用会直接泄露明文异或值，等于把加密废掉。
 *
 * <p>主密钥来自环境变量 {@code APP_ENC_KEY}（Base64 的 32 字节）。没配时
 * {@link #configured()} 为 false，保存 Key 的接口会拒绝写入而不是静默降级成明文。
 */
@Component
public class SecretBox {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretBox(@Value("${app.ai.enc-key:}") String base64Key) {
        SecretKeySpec parsed = null;
        if (base64Key != null && !base64Key.isBlank()) {
            byte[] raw;
            try {
                raw = Base64.getDecoder().decode(base64Key.trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("APP_ENC_KEY 不是合法的 Base64", e);
            }
            if (raw.length != 32) {
                // 静默补齐/截断会让「换机器后解不开」变成悬案，不如启动就说清楚
                throw new IllegalStateException(
                        "APP_ENC_KEY 必须是 32 字节（AES-256）的 Base64，当前解码后为 " + raw.length + " 字节");
            }
            parsed = new SecretKeySpec(raw, "AES");
        }
        this.key = parsed;
    }

    /** 未配置主密钥时不能存 Key：宁可让功能不可用，也不留一条「其实是明文」的路。 */
    public boolean configured() {
        return key != null;
    }

    public byte[] encrypt(String plain) {
        if (key == null) {
            throw new ApiException(ErrorCode.INTERNAL, "服务端未配置 APP_ENC_KEY，无法保存 API Key");
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));

            byte[] out = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(sealed, 0, out, iv.length, sealed.length);
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("加密 API Key 失败", e);
        }
    }

    /** 解密失败统一报「需要重新填 Key」：密文损坏或主密钥换过，用户能做的只有重填。 */
    public String decrypt(byte[] stored) {
        if (key == null) {
            throw new ApiException(ErrorCode.AI_NOT_CONFIGURED);
        }
        if (stored == null || stored.length <= IV_BYTES) {
            throw new ApiException(ErrorCode.AI_NOT_CONFIGURED);
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_BITS, stored, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(stored, IV_BYTES, stored.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new ApiException(ErrorCode.AI_NOT_CONFIGURED,
                    "API Key 无法解密（主密钥可能已更换），请重新填写");
        }
    }

    /**
     * 掩码：只留头 3 位与尾 4 位，如 {@code sk-****3f8a}。
     *
     * <p>为什么不全遮：用户有多个 Key 时需要辨认「填的是哪一个」，
     * 留 7 个字符不足以还原，又能区分。
     */
    public static String mask(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        String trimmed = apiKey.trim();
        if (trimmed.length() <= 8) {
            return "****";
        }
        return trimmed.substring(0, 3) + "****" + trimmed.substring(trimmed.length() - 4);
    }
}
