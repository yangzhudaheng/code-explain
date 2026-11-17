package com.codeexplain.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * HS256 JWT 的签发与校验。
 *
 * <p>没有引 jjwt：这里只需要「签名 + 过期」，手写反而更容易审。
 * 但有三个坑必须显式处理，否则等于没做认证：
 *
 * <ol>
 *   <li>只接受 <b>HS256</b>。不校验 {@code alg} 就信任 header 是经典漏洞（攻击者写
 *       {@code {"alg":"none"}} 或换成非对称算法，用公钥当密钥验签）。</li>
 *   <li>签名比对必须用 {@link MessageDigest#isEqual}（常量时间），
 *       用 {@code equals} 会被逐字节计时攻击还原签名。</li>
 *   <li>密钥不足 32 字节直接拒绝启动。短密钥可暴力破解，宁可起不来也不静默降级。</li>
 * </ol>
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    /** 留给分布式部署的时钟漂移余量 */
    private static final long LEEWAY_SECONDS = 60;

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    private static final String HEADER_JSON = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";

    private final byte[] key;
    private final Duration ttl;
    private final ObjectMapper json;

    public JwtService(@Value("${app.auth.jwt-secret:}") String secret,
                      @Value("${app.auth.token-ttl:PT168H}") Duration ttl,
                      ObjectMapper json) {
        this.key = resolveKey(secret);
        this.ttl = ttl;
        this.json = json;
    }

    /**
     * 配了密钥就严格校验长度；没配才生成临时密钥。
     *
     * <p>宽松处理「没配」是为了让本地开发和 CI 不必人手一份密钥；
     * 但绝不宽松处理「配了但太短」——那是最危险的情况：看起来配好了，实际可被暴力破解。
     */
    private static byte[] resolveKey(String secret) {
        if (secret == null || secret.isBlank()) {
            byte[] ephemeral = new byte[48];
            new java.security.SecureRandom().nextBytes(ephemeral);
            log.warn("未配置 JWT_SECRET，已生成临时密钥：本次进程内有效，重启后所有登录态失效。"
                    + "线上请用 openssl rand -base64 48 生成并配置");
            return ephemeral;
        }
        byte[] raw = secret.getBytes(StandardCharsets.UTF_8);
        if (raw.length < 32) {
            throw new IllegalStateException(
                    "app.auth.jwt-secret 长度不足 32 字节（当前 " + raw.length + "），请用 openssl rand -base64 48 生成");
        }
        return raw;
    }

    public Duration ttl() {
        return ttl;
    }

    public String issue(UUID userId, String email) {
        long now = Instant.now().getEpochSecond();
        String payload = String.format(
                "{\"sub\":\"%s\",\"email\":\"%s\",\"iat\":%d,\"exp\":%d}",
                userId, escape(email), now, now + ttl.getSeconds());
        String head = B64.encodeToString(HEADER_JSON.getBytes(StandardCharsets.UTF_8));
        String body = B64.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return head + "." + body + "." + B64.encodeToString(sign(head + "." + body));
    }

    /** 校验通过返回主体；任何一步不成立都返回空，调用方统一按「未登录」处理。 */
    public Optional<Claims> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            JsonNode header = json.readTree(B64D.decode(parts[0]));
            // 只认 HS256：拦住 alg=none 与算法混淆
            if (!"HS256".equals(header.path("alg").asText())) {
                return Optional.empty();
            }

            byte[] expected = sign(parts[0] + "." + parts[1]);
            byte[] actual = B64D.decode(parts[2]);
            if (!MessageDigest.isEqual(expected, actual)) {
                return Optional.empty();
            }

            JsonNode claims = json.readTree(B64D.decode(parts[1]));
            long exp = claims.path("exp").asLong(0);
            if (exp == 0 || Instant.now().getEpochSecond() > exp + LEEWAY_SECONDS) {
                return Optional.empty();
            }
            UUID userId = UUID.fromString(claims.path("sub").asText());
            return Optional.of(new Claims(userId, claims.path("email").asText(), Instant.ofEpochSecond(exp)));
        } catch (Exception e) {
            // 结构非法、base64 坏掉、UUID 解析失败 —— 都是「凭证无效」，不值得打日志刷屏
            log.debug("JWT 校验失败: {}", e.toString());
            return Optional.empty();
        }
    }

    private byte[] sign(String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(input.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 不可用", e);
        }
    }

    /** email 进 JSON 前转义，避免引号/反斜杠破坏结构。 */
    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 令牌内携带的最小信息：谁能看、什么时候失效。 */
    public record Claims(UUID userId, String email, Instant expiresAt) {
    }
}
