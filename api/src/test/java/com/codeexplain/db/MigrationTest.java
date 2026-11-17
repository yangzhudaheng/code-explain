package com.codeexplain.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 真跑一遍迁移的集成测试。
 *
 * <p>用内嵌 PostgreSQL 而不是 H2：迁移里有 jsonb、`::jsonb` 转换、部分索引、check 约束，
 * H2 兼容模式跑不通这些，测出来的「通过」是假的。
 */
// 显式清空 DATABASE_URL：本地 api/.env 里有真实 Neon 连接串，
// 不屏蔽的话这个测试会连到线上库并 insert/delete 数据。
@SpringBootTest(properties = {"DATABASE_URL=", "DB_USER=", "DB_PASSWORD="})
@AutoConfigureMockMvc
class MigrationTest {

    private static final EmbeddedPostgres PG = startPostgres();

    private static EmbeddedPostgres startPostgres() {
        try {
            return EmbeddedPostgres.builder().setPort(0).start();
        } catch (Exception e) {
            throw new IllegalStateException("内嵌 PostgreSQL 启动失败", e);
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        // 走 DB_URL 分支，同时验证 jdbc:postgresql:// 写法里的 user 参数能被正确抽取
        registry.add("DB_URL", () -> PG.getJdbcUrl("postgres", "postgres"));
    }

    @AfterAll
    static void shutdown() throws Exception {
        PG.close();
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("迁移建出全部表，且 schema 版本为 1")
    void createsAllTables() {
        List<String> tables = jdbc.queryForList(
                "select tablename from pg_tables where schemaname = 'public'", String.class);

        assertThat(tables).contains(
                "users", "email_codes", "canvases", "questions", "answers", "user_ai_settings",
                "flyway_schema_history");

        String version = jdbc.queryForObject(
                "select version from flyway_schema_history where success order by installed_rank desc limit 1",
                String.class);
        assertThat(version).isEqualTo("1");
    }

    @Test
    @DisplayName("jsonb 列可写可读，锚点里的字段能直接查询")
    void jsonbRoundTrip() {
        UUID userId = jdbc.queryForObject(
                "insert into users (email, password_hash) values (?, ?) returning id",
                UUID.class, "jsonb@example.com", "$2a$10$fake");

        UUID canvasId = jdbc.queryForObject(
                "insert into canvases (user_id, title, data) values (?, ?, ?::jsonb) returning id",
                UUID.class, userId, "二分查找",
                "{\"viewport\":{\"x\":0,\"y\":0,\"zoom\":1},\"blocks\":[{\"id\":\"b_1\",\"content\":\"class A {}\"}]}");

        jdbc.update("""
                insert into questions (canvas_id, block_id, anchor, text, side)
                values (?, ?, ?::jsonb, ?, ?)
                """, canvasId, "b_1",
                "{\"startOffset\":6,\"endOffset\":18,\"startLine\":2,\"startChar\":4,\"quote\":\"while (lo < hi)\"}",
                "这里为什么用 lo < hi？", "left");

        assertThat(jdbc.queryForObject(
                "select anchor->>'quote' from questions where canvas_id = ?", String.class, canvasId))
                .isEqualTo("while (lo < hi)");
        assertThat(jdbc.queryForObject(
                "select side from questions where canvas_id = ?", String.class, canvasId))
                .isEqualTo("left");
        assertThat(jdbc.queryForObject(
                "select data->'blocks'->0->>'id' from canvases where id = ?", String.class, canvasId))
                .isEqualTo("b_1");
    }

    @Test
    @DisplayName("同一邮箱大小写不同视为冲突")
    void emailIsCaseInsensitiveUnique() {
        jdbc.update("insert into users (email, password_hash) values (?, ?)", "Mix@Example.com", "x");

        assertThatThrownBy(() ->
                jdbc.update("insert into users (email, password_hash) values (?, ?)", "mix@example.com", "x"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("非法枚举值被 check 约束拦住")
    void rejectsInvalidEnum() {
        UUID userId = jdbc.queryForObject(
                "insert into users (email, password_hash) values (?, ?) returning id",
                UUID.class, "enum@example.com", "x");
        UUID canvasId = jdbc.queryForObject(
                "insert into canvases (user_id) values (?) returning id", UUID.class, userId);

        assertThatThrownBy(() -> jdbc.update("""
                insert into questions (canvas_id, block_id, anchor, text, side)
                values (?, 'b_1', '{}'::jsonb, 'q', 'up')
                """, canvasId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("删画布级联删掉提问与回答")
    void cascadesOnDelete() {
        UUID userId = jdbc.queryForObject(
                "insert into users (email, password_hash) values (?, ?) returning id",
                UUID.class, "cascade@example.com", "x");
        UUID canvasId = jdbc.queryForObject(
                "insert into canvases (user_id) values (?) returning id", UUID.class, userId);
        UUID questionId = jdbc.queryForObject("""
                insert into questions (canvas_id, block_id, anchor, text)
                values (?, 'b_1', '{}'::jsonb, 'q') returning id
                """, UUID.class, canvasId);
        jdbc.update("insert into answers (question_id, content, status) values (?, 'ok', 'done')", questionId);

        jdbc.update("delete from canvases where id = ?", canvasId);

        // 按 id 断言而不是全表 count：多个测试共用一个库，全表 count 会互相干扰
        assertThat(jdbc.queryForObject(
                "select count(*) from questions where canvas_id = ?", Integer.class, canvasId)).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from answers where question_id = ?", Integer.class, questionId)).isZero();
    }

    @Test
    @DisplayName("/api/db/ping 返回连通信息、schema 版本与表清单")
    void healthEndpointReportsSchema() throws Exception {
        mvc.perform(get("/api/db/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.schemaVersion").value("1"))
                .andExpect(jsonPath("$.server").value(org.hamcrest.Matchers.containsString("PostgreSQL")))
                .andExpect(jsonPath("$.tables", hasItem("questions")))
                .andExpect(jsonPath("$.tables", hasItem("canvases")));
    }

    @Test
    @DisplayName("护栏：测试必须连本机内嵌库，绝不能连到线上 Neon")
    void neverRunsAgainstRemoteDatabase() {
        // host() 去掉 inet 的掩码，否则返回 "127.0.0.1/32"
        String addr = jdbc.queryForObject(
                "select coalesce(host(inet_server_addr()), '127.0.0.1')", String.class);
        assertThat(addr)
                .as("测试会 insert/delete 数据，连到远端库就是事故。检查 api/.env 是否被测试读到")
                // AssertJ 的 matches() 是整串匹配，不是前缀匹配
                .matches("^(127\\.\\d+\\.\\d+\\.\\d+|::1|0\\.0\\.0\\.0)$");
    }
}
