package com.codeexplain.canvas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.MvcResult;

/**
 * 画布接口的端到端测试。
 *
 * <p>上限与大小限制在测试里调小（2 个画布 / 1KB），否则为了验证一条边界规则
 * 要真的建 50 个画布、拼 2MB 字符串，测试会又慢又难读。
 *
 * <p>登录态直接用 {@link JwtService} 签发 Cookie，不走注册链路：
 * 注册流程本身由 {@code AuthFlowTest} 覆盖，这里再跑一遍只是白等 BCrypt。
 */
@SpringBootTest(properties = {
        "DATABASE_URL=",
        "DB_USER=",
        "DB_PASSWORD=",
        "spring.mail.username=",
        "spring.mail.password=",
        "app.auth.jwt-secret=test-only-secret-0123456789abcdefghijklmnopqrstuv",
        "app.auth.cookie-secure=false",
        "app.canvas.max-per-user=2",
        "app.canvas.max-data-bytes=1024"
})
@AutoConfigureMockMvc
class CanvasApiTest {

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

    // ---------------------------------------------------------------- 工具

    private static final String DOC = """
            {"viewport":{"x":10,"y":20,"zoom":1.5},
             "blocks":[{"id":"b_1","x":80,"y":60,"width":520,"title":"A.java",
                        "language":"java","content":"class A {}"}],
             "questions":[],
             "bg":{"pattern":"dot","tone":"warm"}}
            """;

    private UUID newUser(String emailPrefix) {
        return jdbc.queryForObject(
                "insert into users (email, password_hash) values (?, ?) returning id",
                UUID.class, emailPrefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com",
                "$2a$10$fake");
    }

    private Cookie cookieOf(UUID userId) {
        return new Cookie(SessionCookies.NAME, jwt.issue(userId, "u@example.com"));
    }

    private MvcResult create(UUID userId, String title) throws Exception {
        String body = title == null ? "{}" : """
                {"title":"%s","data":%s}
                """.formatted(title, DOC);
        return mvc.perform(post("/api/canvases")
                        .cookie(cookieOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
    }

    private String createAndGetId(UUID userId) throws Exception {
        MvcResult res = create(userId, "画布");
        assertThat(res.getResponse().getStatus()).isEqualTo(201);
        return com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.id");
    }

    private String saveBody(String title, int baseVersion) {
        return """
                {"title":"%s","data":%s,"baseVersion":%d}
                """.formatted(title, DOC, baseVersion);
    }

    // ---------------------------------------------------------------- 用例

    @Test
    @DisplayName("未登录访问画布列表返回 401")
    void requiresLogin() throws Exception {
        mvc.perform(get("/api/canvases"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("创建后可读回，data 完整往返且 version 从 0 开始")
    void createAndReadBack() throws Exception {
        UUID user = newUser("create");

        MvcResult res = create(user, "二分查找");
        assertThat(res.getResponse().getStatus()).isEqualTo(201);
        String id = com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.id");
        assertThat(com.jayway.jsonpath.JsonPath.<Integer>read(
                res.getResponse().getContentAsString(), "$.version")).isZero();

        mvc.perform(get("/api/canvases/" + id).cookie(cookieOf(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("二分查找"))
                .andExpect(jsonPath("$.data.blocks[0].content").value("class A {}"))
                .andExpect(jsonPath("$.data.viewport.zoom").value(1.5))
                .andExpect(jsonPath("$.data.bg.pattern").value("dot"));

        mvc.perform(get("/api/canvases").cookie(cookieOf(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].blockCount").value(1))
                .andExpect(jsonPath("$[0].questionCount").value(0));
    }

    @Test
    @DisplayName("不传 title/data 时给出可渲染的空画布骨架")
    void createWithDefaults() throws Exception {
        UUID user = newUser("default");
        MvcResult res = create(user, null);
        String id = com.jayway.jsonpath.JsonPath.read(res.getResponse().getContentAsString(), "$.id");

        mvc.perform(get("/api/canvases/" + id).cookie(cookieOf(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("未命名画布"))
                .andExpect(jsonPath("$.data.viewport.x").value(120))
                .andExpect(jsonPath("$.data.blocks.length()").value(0))
                .andExpect(jsonPath("$.data.questions.length()").value(0));
    }

    @Test
    @DisplayName("版本号匹配时保存成功，version 递增")
    void saveWithMatchingVersion() throws Exception {
        UUID user = newUser("save");
        String id = createAndGetId(user);

        mvc.perform(put("/api/canvases/" + id)
                        .cookie(cookieOf(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saveBody("改名了", 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.title").value("改名了"));
    }

    @Test
    @DisplayName("版本号过期保存返回 409，且库里的内容不被旧快照覆盖")
    void staleVersionIsRejected() throws Exception {
        UUID user = newUser("stale");
        String id = createAndGetId(user);

        mvc.perform(put("/api/canvases/" + id).cookie(cookieOf(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saveBody("第一次", 0)))
                .andExpect(status().isOk());

        mvc.perform(put("/api/canvases/" + id).cookie(cookieOf(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saveBody("另一个标签页", 0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANVAS_VERSION_CONFLICT"));

        // 关键断言：409 不能是「报了错但还是写进去了」
        assertThat(jdbc.queryForObject(
                "select title from canvases where id = ?", String.class, UUID.fromString(id)))
                .isEqualTo("第一次");
        assertThat(jdbc.queryForObject(
                "select version from canvases where id = ?", Integer.class, UUID.fromString(id)))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("别人的画布一律 404，不泄露是否存在")
    void otherUsersCanvasIsInvisible() throws Exception {
        UUID owner = newUser("owner");
        UUID intruder = newUser("intruder");
        String id = createAndGetId(owner);

        mvc.perform(get("/api/canvases/" + id).cookie(cookieOf(intruder)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CANVAS_NOT_FOUND"));

        mvc.perform(put("/api/canvases/" + id).cookie(cookieOf(intruder))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(saveBody("偷改", 0)))
                .andExpect(status().isNotFound());

        mvc.perform(delete("/api/canvases/" + id).cookie(cookieOf(intruder)))
                .andExpect(status().isNotFound());

        // 列表里也不能出现
        mvc.perform(get("/api/canvases").cookie(cookieOf(intruder)))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("删除是软删：列表和详情都查不到，但数据行还在")
    void deleteHidesCanvas() throws Exception {
        UUID user = newUser("delete");
        String id = createAndGetId(user);

        mvc.perform(delete("/api/canvases/" + id).cookie(cookieOf(user)))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/canvases/" + id).cookie(cookieOf(user)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/canvases").cookie(cookieOf(user)))
                .andExpect(jsonPath("$.length()").value(0));

        assertThat(jdbc.queryForObject(
                "select deleted_at is not null from canvases where id = ?",
                Boolean.class, UUID.fromString(id))).isTrue();
    }

    @Test
    @DisplayName("data 不是 JSON 对象时返回 400 并指出字段")
    void rejectsNonObjectData() throws Exception {
        UUID user = newUser("bad-data");
        mvc.perform(post("/api/canvases").cookie(cookieOf(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"x","data":[1,2,3]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.field").value("data"));
    }

    @Test
    @DisplayName("超过大小上限返回 413")
    void rejectsOversizedSnapshot() throws Exception {
        UUID user = newUser("huge");
        String big = "a".repeat(2000);
        String body = """
                {"title":"大","data":{"blocks":[{"id":"b_1","content":"%s"}]}}
                """.formatted(big);

        mvc.perform(post("/api/canvases").cookie(cookieOf(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("CANVAS_TOO_LARGE"));
    }

    @Test
    @DisplayName("画布数量到上限后创建返回 409")
    void enforcesCanvasLimit() throws Exception {
        UUID user = newUser("limit");
        createAndGetId(user);
        createAndGetId(user);

        mvc.perform(post("/api/canvases").cookie(cookieOf(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANVAS_LIMIT_REACHED"));

        // 软删一个后又能建，说明上限算的是「未删除」的画布
        String first = jdbc.queryForObject(
                "select id::text from canvases where user_id = ? and deleted_at is null limit 1",
                String.class, user);
        mvc.perform(delete("/api/canvases/" + first).cookie(cookieOf(user)))
                .andExpect(status().isNoContent());
        createAndGetId(user);
    }

    @Test
    @DisplayName("标题超长返回 400")
    void rejectsLongTitle() throws Exception {
        UUID user = newUser("title");
        mvc.perform(post("/api/canvases").cookie(cookieOf(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"%s"}
                                """.formatted("标".repeat(121))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
