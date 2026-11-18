package com.codeexplain.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 图形码的行为测试。
 *
 * <p>为什么不再是「纯单元测试」：答案从 Caffeine 搬到数据库后，一次性消费语义由
 * {@code delete ... returning} 保证，这行 SQL 必须真跑一遍才敢说它是对的。
 * 用内嵌 PostgreSQL 而不是 mock 掉 JdbcTemplate——mock 只能验证「我调了这个方法」，
 * 验证不了「两实例并发时只有一个成功」。
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
class CaptchaServiceTest {

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
    private CaptchaService service;

    @Autowired
    private JdbcTemplate jdbc;

    private String newId() {
        return UUID.randomUUID().toString();
    }

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
        String id = newId();
        service.putForTest(id, "42");

        assertThat(service.verifyAndConsume(id, "42")).isTrue();
        assertThat(service.verifyAndConsume(id, "42")).isFalse();
    }

    @Test
    @DisplayName("答案错误时不通过，且该 id 已被销毁（防止无限试错）")
    void wrongAnswerBurnsId() {
        String id = newId();
        service.putForTest(id, "7");

        assertThat(service.verifyAndConsume(id, "8")).isFalse();
        assertThat(service.verifyAndConsume(id, "7")).isFalse();
    }

    @Test
    @DisplayName("不存在的 id、空输入都不通过")
    void handlesMissingInput() {
        assertThat(service.verifyAndConsume("nope", "1")).isFalse();
        assertThat(service.verifyAndConsume(null, "1")).isFalse();
        assertThat(service.verifyAndConsume("x", null)).isFalse();
        assertThat(service.verifyAndConsume("x", "   ")).isFalse();
    }

    @Test
    @DisplayName("答案大小写不敏感（图上是大写，用户常输小写）")
    void answerIsCaseInsensitive() {
        String id = newId();
        service.putForTest(id, "AB");

        assertThat(service.verifyAndConsume(id, "ab")).isTrue();
    }

    @Test
    @DisplayName("库里只存答案哈希，不是明文")
    void answerIsHashed() {
        String id = newId();
        service.putForTest(id, "9Z8Y");

        String stored = jdbc.queryForObject(
                "select answer_hash from captcha_sessions where id = ?", String.class, id);
        assertThat(stored).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(stored).doesNotContain("9Z8Y");
    }

    @Test
    @DisplayName("过期的图形码即使答案正确也不通过")
    void expiredRejected() {
        String id = newId();
        service.putForTest(id, "55");
        // 直接把有效期挪到过去，模拟用户开着页面去泡了杯咖啡
        jdbc.update("update captcha_sessions set expires_at = ? where id = ?",
                OffsetDateTime.ofInstant(Instant.now().minusSeconds(1), ZoneOffset.UTC), id);

        assertThat(service.verifyAndConsume(id, "55")).isFalse();
    }

    @Test
    @DisplayName("两个实例共享库时只有一个消费成功（不依赖进程内状态）")
    void crossInstanceSingleUse() {
        String id = newId();
        service.putForTest(id, "77");

        // 模拟第二个容器实例：同一张表、不同 JVM
        var other = new CaptchaService(new CaptchaRepository(jdbc), false);

        assertThat(service.verifyAndConsume(id, "77")).isTrue();
        assertThat(other.verifyAndConsume(id, "77")).isFalse();
    }
}
