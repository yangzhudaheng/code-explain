package com.codeexplain.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import com.codeexplain.auth.JwtService;
import com.codeexplain.auth.SessionCookies;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 模型设置接口的端到端测试。
 *
 * <p>这个测试最重要的断言不是「返回 200」，而是<b>库里没有明文 Key</b>——
 * 那是本项目唯一会接触到用户付费凭据的地方。
 *
 * <p>base-url 用字面量公网 IP（8.8.8.8）而不是真实域名：
 * {@link BaseUrlGuard} 会解析域名，测试不该依赖 DNS。
 */
@SpringBootTest(properties = {
        "DATABASE_URL=",
        "DB_USER=",
        "DB_PASSWORD=",
        "spring.mail.username=",
        "spring.mail.password=",
        "app.auth.jwt-secret=test-only-secret-0123456789abcdefghijklmnopqrstuv",
        "app.auth.cookie-secure=false",
        // 32 字节的 Base64，测试专用
        "app.ai.enc-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
})
@AutoConfigureMockMvc
class AiSettingsApiTest {

    private static final String PLAIN_KEY = "sk-test-1234567890abcdef";

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
    private JdbcTemplate jdbc;

    @Autowired
    private JwtService jwt;

    private UUID newUser() {
        return jdbc.queryForObject(
                "insert into users (email, password_hash) values (?, ?) returning id",
                UUID.class, "ai-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com",
                "$2a$10$fake");
    }

    private Cookie cookieOf(UUID userId) {
        return new Cookie(SessionCookies.NAME, jwt.issue(userId, "u@example.com"));
    }

    private String saveBody(String baseUrl, String model, String apiKey) {
        return """
                {"provider":"deepseek","baseUrl":"%s","model":"%s","apiKey":%s}
                """.formatted(baseUrl, model, apiKey == null ? "null" : "\"" + apiKey + "\"");
    }

    @Test
    @DisplayName("未登录访问返回 401")
    void requiresSession() throws Exception {
        mvc.perform(get("/api/me/ai-settings")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("没配置过时返回 DeepSeek 预设，且 hasKey=false")
    void returnsPresetWithoutKey() throws Exception {
        UUID userId = newUser();

        mvc.perform(get("/api/me/ai-settings").cookie(cookieOf(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("deepseek"))
                .andExpect(jsonPath("$.baseUrl").value("https://api.deepseek.com"))
                .andExpect(jsonPath("$.model").value("deepseek-chat"))
                .andExpect(jsonPath("$.hasKey").value(false))
                .andExpect(jsonPath("$.keyEncryptionReady").value(true))
                .andExpect(jsonPath("$.presets[0].id").value("deepseek"));
    }

    @Test
    @DisplayName("保存 Key 后只回掩码，库里存的是密文")
    void savesKeyEncrypted() throws Exception {
        UUID userId = newUser();

        mvc.perform(put("/api/me/ai-settings").cookie(cookieOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saveBody("https://8.8.8.8", "deepseek-chat", PLAIN_KEY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasKey").value(true))
                .andExpect(jsonPath("$.apiKeyMask").value("sk-****cdef"))
                // 接口绝不能把明文回给前端
                .andExpect(jsonPath("$.apiKey").doesNotExist());

        byte[] cipher = jdbc.queryForObject(
                "select api_key_cipher from user_ai_settings where user_id = ?", byte[].class, userId);
        assertThat(cipher).isNotNull();
        assertThat(new String(cipher, StandardCharsets.ISO_8859_1)).doesNotContain(PLAIN_KEY);

        String mask = jdbc.queryForObject(
                "select api_key_mask from user_ai_settings where user_id = ?", String.class, userId);
        assertThat(mask).isEqualTo("sk-****cdef");
    }

    @Test
    @DisplayName("只改模型名不带 Key 时，原 Key 保留")
    void keepsKeyWhenOnlyModelChanges() throws Exception {
        UUID userId = newUser();
        mvc.perform(put("/api/me/ai-settings").cookie(cookieOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saveBody("https://8.8.8.8", "deepseek-chat", PLAIN_KEY)))
                .andExpect(status().isOk());

        mvc.perform(put("/api/me/ai-settings").cookie(cookieOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saveBody("https://8.8.8.8", "deepseek-reasoner", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model").value("deepseek-reasoner"))
                .andExpect(jsonPath("$.hasKey").value(true))
                .andExpect(jsonPath("$.apiKeyMask").value("sk-****cdef"));
    }

    @Test
    @DisplayName("clearKey 清掉密文与掩码")
    void clearsKey() throws Exception {
        UUID userId = newUser();
        mvc.perform(put("/api/me/ai-settings").cookie(cookieOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saveBody("https://8.8.8.8", "deepseek-chat", PLAIN_KEY)))
                .andExpect(status().isOk());

        mvc.perform(put("/api/me/ai-settings").cookie(cookieOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"deepseek\",\"clearKey\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasKey").value(false))
                .andExpect(jsonPath("$.apiKeyMask").doesNotExist());

        byte[] cipher = jdbc.queryForObject(
                "select api_key_cipher from user_ai_settings where user_id = ?", byte[].class, userId);
        assertThat(cipher).isNull();
    }

    @Test
    @DisplayName("内网 / http / localhost 的 base-url 被拒绝，且不落库")
    void rejectsUnsafeBaseUrl() throws Exception {
        UUID userId = newUser();

        for (String url : new String[]{
                "http://api.deepseek.com", "https://localhost:8080",
                "https://127.0.0.1", "https://169.254.169.254"}) {
            mvc.perform(put("/api/me/ai-settings").cookie(cookieOf(userId))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(saveBody(url, "deepseek-chat", PLAIN_KEY)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("BASE_URL_FORBIDDEN"));
        }

        Integer rows = jdbc.queryForObject(
                "select count(*) from user_ai_settings where user_id = ?", Integer.class, userId);
        assertThat(rows).isZero();
    }

    @Test
    @DisplayName("没配 Key 时点测试：ok=false 且给出可读原因，不是 500")
    void testWithoutKeyReportsReason() throws Exception {
        UUID userId = newUser();

        mvc.perform(post("/api/me/ai-settings/test").cookie(cookieOf(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.error").isNotEmpty());
    }
}
