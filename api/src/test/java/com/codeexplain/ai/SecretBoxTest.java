package com.codeexplain.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.codeexplain.common.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SecretBoxTest {

    private static final String KEY = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef"
            .getBytes(StandardCharsets.UTF_8));

    @Test
    @DisplayName("加密后能解回原文")
    void roundTrip() {
        var box = new SecretBox(KEY);
        byte[] sealed = box.encrypt("sk-1234567890abcdef");

        assertThat(box.decrypt(sealed)).isEqualTo("sk-1234567890abcdef");
        // 密文里不该出现明文片段
        assertThat(new String(sealed, StandardCharsets.ISO_8859_1)).doesNotContain("sk-1234");
    }

    @Test
    @DisplayName("同一明文两次加密得到不同密文（IV 随机）")
    void ivIsRandom() {
        var box = new SecretBox(KEY);

        assertThat(box.encrypt("same-plaintext")).isNotEqualTo(box.encrypt("same-plaintext"));
    }

    @Test
    @DisplayName("密文被改一个字节就解不开（GCM 认证标签生效）")
    void tamperedCipherRejected() {
        var box = new SecretBox(KEY);
        byte[] sealed = box.encrypt("sk-secret");
        sealed[sealed.length - 1] ^= 0x01;

        assertThatThrownBy(() -> box.decrypt(sealed)).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("换了主密钥就解不开旧密文，且提示用户重填")
    void differentKeyCannotDecrypt() {
        byte[] sealed = new SecretBox(KEY).encrypt("sk-secret");
        var other = new SecretBox(Base64.getEncoder().encodeToString(
                "ffffffffffffffffffffffffffffffff".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> other.decrypt(sealed))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("重新填写");
    }

    @Test
    @DisplayName("没配主密钥时不能加密，也不能假装已配置")
    void unconfiguredRefusesToEncrypt() {
        var box = new SecretBox("");

        assertThat(box.configured()).isFalse();
        assertThatThrownBy(() -> box.encrypt("sk-secret")).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("主密钥长度不对时启动即失败，不静默截断补齐")
    void wrongKeyLengthFailsFast() {
        String shortKey = Base64.getEncoder().encodeToString("too-short".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> new SecretBox(shortKey))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 字节");
    }

    @Test
    @DisplayName("掩码只留头 3 位与尾 4 位")
    void maskKeepsEnds() {
        assertThat(SecretBox.mask("sk-1234567890abcdef")).isEqualTo("sk-****cdef");
        assertThat(SecretBox.mask("short")).isEqualTo("****");
        assertThat(SecretBox.mask("   ")).isNull();
        assertThat(SecretBox.mask(null)).isNull();
    }
}
