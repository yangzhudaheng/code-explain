package com.codeexplain.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CaptchaServiceTest {

    private final CaptchaService service = new CaptchaService(false);

    @Test
    @DisplayName("生成的是可直接放进 img src 的 PNG data URL")
    void createsDataUrl() {
        var captcha = service.create();
        assertThat(captcha.id()).isNotBlank();
        assertThat(captcha.imageBase64()).startsWith("data:image/png;base64,");
        // 空图/纯白图会非常小，这里断言确实画出了东西
        assertThat(captcha.imageBase64().length()).isGreaterThan(1000);
    }

    @Test
    @DisplayName("答案只能用一次：第二次校验必须失败")
    void singleUse() {
        CaptchaService svc = new CaptchaService(false);
        svc.putForTest("id1", "42");

        assertThat(svc.verifyAndConsume("id1", "42")).isTrue();
        assertThat(svc.verifyAndConsume("id1", "42")).isFalse();
    }

    @Test
    @DisplayName("答案错误时不通过，且该 id 已被销毁（防止无限试错）")
    void wrongAnswerBurnsId() {
        CaptchaService svc = new CaptchaService(false);
        svc.putForTest("id2", "7");

        assertThat(svc.verifyAndConsume("id2", "8")).isFalse();
        assertThat(svc.verifyAndConsume("id2", "7")).isFalse();
    }

    @Test
    @DisplayName("不存在的 id、空输入都不通过")
    void handlesMissingInput() {
        assertThat(service.verifyAndConsume("nope", "1")).isFalse();
        assertThat(service.verifyAndConsume(null, "1")).isFalse();
        assertThat(service.verifyAndConsume("x", null)).isFalse();
    }
}
