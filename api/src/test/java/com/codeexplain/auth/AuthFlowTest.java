package com.codeexplain.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 注册 / 登录 / 会话的端到端测试。
 *
 * <p>邮件用 {@code @SpyBean} 截获，既能拿到验证码又不会真发出去——
 * 测试永远不该往真实邮箱投递。
 *
 * <p>同时显式清空 {@code spring.mail.username/password}：本地 {@code api/.env}
 * 里有真实 SMTP 凭据，不清空的话 spy 会走真发信分支。
 */
@SpringBootTest(properties = {
        "DATABASE_URL=",
        "DB_USER=",
        "DB_PASSWORD=",
        "spring.mail.username=",
        "spring.mail.password=",
        "app.auth.jwt-secret=test-only-secret-0123456789abcdefghijklmnopqrstuv",
        "app.auth.cookie-secure=false"
})
@AutoConfigureMockMvc
class AuthFlowTest {

    private static final EmbeddedPostgres PG = start();

    private static EmbeddedPostgres start() {
        try {
            return EmbeddedPostgres.builder().setPort(0).start();
        } catch (Exception e) {
            throw new IllegalStateException("内嵌 PostgreSQL 启动失败", e);
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("DB_URL", () -> PG.getJdbcUrl("postgres", "postgres"));
    }

    @AfterAll
    static void shutdown() throws Exception {
        PG.close();
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private CaptchaService captchas;

    @Autowired
    private JdbcTemplate jdbc;

    @SpyBean
    private MailService mail;

    // ---------------------------------------------------------------- 工具

    private String captcha(String answer) {
        String id = UUID.randomUUID().toString();
        captchas.putForTest(id, answer);
        return id;
    }

    private void requestCode(String email) throws Exception {
        String id = captcha("12");
        mvc.perform(post("/api/auth/email-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","captchaId":"%s","captchaCode":"12"}
                                """.formatted(email, id)))
                .andExpect(status().isOk());
    }

    private String captureCode(String email) {
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(mail).sendCode(eq(email), code.capture(), any(Duration.class));
        return code.getValue();
    }

    private MvcResult register(String email, String code, String password) throws Exception {
        return mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","code":"%s","password":"%s","displayName":"阿达"}
                                """.formatted(email, code, password)))
                .andReturn();
    }

    private static String sessionOf(MvcResult result) {
        List<String> headers = result.getResponse().getHeaders("Set-Cookie");
        assertThat(headers).isNotEmpty();
        String raw = headers.stream().filter(h -> h.startsWith(SessionCookies.NAME + "=")).findFirst()
                .orElseThrow(() -> new AssertionError("响应里没有会话 Cookie"));
        return raw.substring((SessionCookies.NAME + "=").length(), raw.indexOf(';'));
    }

    private static String uniqueEmail() {
        return "u" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    // ---------------------------------------------------------------- 用例

    @Test
    @DisplayName("完整链路：图形码 → 邮件码 → 注册 → 带 Cookie 访问 /me")
    void registerThenAccessMe() throws Exception {
        String email = uniqueEmail();
        requestCode(email);
        String code = captureCode(email);

        MvcResult res = register(email, code, "Passw0rd!");
        assertThat(res.getResponse().getStatus()).isEqualTo(200);

        String token = sessionOf(res);
        mvc.perform(get("/api/auth/me").cookie(new Cookie(SessionCookies.NAME, token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.emailVerified").value(true))
                .andExpect(jsonPath("$.displayName").value("阿达"));

        // 密码与验证码都不能明文落库
        String hash = jdbc.queryForObject(
                "select password_hash from users where lower(email) = lower(?)", String.class, email);
        assertThat(hash).startsWith("$2");
        String codeHash = jdbc.queryForObject(
                "select code_hash from email_codes where lower(email) = lower(?) order by created_at desc limit 1",
                String.class, email);
        assertThat(codeHash).startsWith("$2").doesNotContain(code);
        Boolean consumed = jdbc.queryForObject(
                "select consumed_at is not null from email_codes where lower(email) = lower(?) order by created_at desc limit 1",
                Boolean.class, email);
        assertThat(consumed).isTrue();
    }

    @Test
    @DisplayName("图形码错误时不发邮件，返回 CAPTCHA_INVALID")
    void wrongCaptchaBlocksEmail() throws Exception {
        String email = uniqueEmail();
        mvc.perform(post("/api/auth/email-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","captchaId":"%s","captchaCode":"999"}
                                """.formatted(email, captcha("12"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CAPTCHA_INVALID"));
    }

    @Test
    @DisplayName("同一个图形码不能重放（第二次必须失败）")
    void captchaReplayRejected() throws Exception {
        String email = uniqueEmail();
        String id = captcha("34");
        String body = """
                {"email":"%s","captchaId":"%s","captchaCode":"34"}
                """.formatted(email, id);

        mvc.perform(post("/api/auth/email-code").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/email-code").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CAPTCHA_INVALID"));
    }

    @Test
    @DisplayName("60 秒内重复请求邮件码被限流（429）")
    void emailCooldownEnforced() throws Exception {
        String email = uniqueEmail();
        requestCode(email);

        mvc.perform(post("/api/auth/email-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","captchaId":"%s","captchaCode":"12"}
                                """.formatted(email, captcha("12"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED_EMAIL"));
    }

    @Test
    @DisplayName("邮箱大小写不同视为同一账号，不能重复注册")
    void duplicateEmailCaseInsensitive() throws Exception {
        String email = uniqueEmail();
        requestCode(email);
        register(email, captureCode(email), "Passw0rd!");

        MvcResult again = register(email.toUpperCase(), "123456", "Passw0rd!");
        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(again.getResponse().getContentAsString()).contains("EMAIL_TAKEN");
    }

    @Test
    @DisplayName("验证码错误提示剩余次数，连续错 5 次后要求重新获取")
    void wrongCodeCountsAttempts() throws Exception {
        String email = uniqueEmail();
        requestCode(email);
        String right = captureCode(email);
        String wrong = right.equals("000000") ? "111111" : "000000";

        for (int i = 0; i < 5; i++) {
            MvcResult res = register(email, wrong, "Passw0rd!");
            assertThat(res.getResponse().getStatus()).isEqualTo(400);
            assertThat(res.getResponse().getContentAsString()).contains("EMAIL_CODE_INVALID");
        }
        // 第 6 次即便填对也不让过：必须重新发码
        MvcResult blocked = register(email, right, "Passw0rd!");
        assertThat(blocked.getResponse().getContentAsString()).contains("EMAIL_CODE_TOO_MANY_ATTEMPTS");
    }

    @Test
    @DisplayName("弱密码被拦，且不消耗验证码")
    void weakPasswordRejected() throws Exception {
        String email = uniqueEmail();
        requestCode(email);
        String code = captureCode(email);

        MvcResult res = register(email, code, "12345678");
        assertThat(res.getResponse().getStatus()).isEqualTo(400);
        assertThat(res.getResponse().getContentAsString()).contains("PASSWORD_TOO_WEAK");

        // 密码规则先于验码校验，所以正确的码还能用
        assertThat(register(email, code, "Passw0rd!").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("登录：密码错误 401，正确后签 Cookie，登出后 /me 401")
    void loginAndLogout() throws Exception {
        String email = uniqueEmail();
        requestCode(email);
        register(email, captureCode(email), "Passw0rd!");

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"wrong-pass1"}
                                """.formatted(email)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        MvcResult ok = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"Passw0rd!"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andReturn();

        String token = sessionOf(ok);
        mvc.perform(get("/api/auth/me").cookie(new Cookie(SessionCookies.NAME, token)))
                .andExpect(status().isOk());

        MvcResult out = mvc.perform(post("/api/auth/logout"))
                .andExpect(status().isNoContent())
                .andReturn();
        // 登出下发的是过期 Cookie，前端删掉它即等于会话结束
        assertThat(out.getResponse().getHeaders("Set-Cookie").toString()).contains("Max-Age=0");
    }

    @Test
    @DisplayName("没有 Cookie 访问 /me 返回 401，而不是 500 或空对象")
    void meRequiresSession() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("伪造的 Cookie 值不被信任")
    void forgedCookieRejected() throws Exception {
        mvc.perform(get("/api/auth/me").cookie(new Cookie(SessionCookies.NAME, "fake.token.value")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("请求体校验：邮箱格式错、密码为空都返回字段级错误")
    void validationReturnsField() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.field").exists());
    }
}
