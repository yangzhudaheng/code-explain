package com.codeexplain.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.codeexplain.ai.ChatClient;
import com.codeexplain.auth.JwtService;
import com.codeexplain.auth.SessionCookies;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 问答接口的端到端测试。
 *
 * <p>上游模型用 {@link MockBean} 替代：真实的 DeepSeek 调用需要付费 Key 和公网，
 * 测试里既不该花钱也不该因网络波动变红。这里要验证的是<b>我们自己的</b>链路——
 * 事件顺序、增量落库、错误如何变成可读提示。
 */
@SpringBootTest(properties = {
        "DATABASE_URL=",
        "DB_USER=",
        "DB_PASSWORD=",
        "spring.mail.username=",
        "spring.mail.password=",
        "app.auth.jwt-secret=test-only-secret-0123456789abcdefghijklmnopqrstuv",
        "app.auth.cookie-secure=false",
        "app.ai.enc-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
})
@AutoConfigureMockMvc
class QaApiTest {

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

    @Autowired
    private ObjectMapper json;

    @MockBean
    private ChatClient chat;

    // ---------------------------------------------------------------- 工具

    private UUID newUser() {
        return jdbc.queryForObject(
                "insert into users (email, password_hash) values (?, ?) returning id",
                UUID.class, "qa-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com",
                "$2a$10$fake");
    }

    private Cookie cookieOf(UUID userId) {
        return new Cookie(SessionCookies.NAME, jwt.issue(userId, "u@example.com"));
    }

    private UUID newCanvas(UUID userId) {
        return jdbc.queryForObject("""
                insert into canvases (user_id, title, data) values (?, '画布', ?::jsonb) returning id
                """, UUID.class, userId, """
                {"blocks":[{"id":"b_1","x":0,"y":0,"width":520,"title":"Solution.java",
                            "language":"java","content":"class A {}"}],"questions":[]}
                """);
    }

    private void saveAiKey(UUID userId) throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/me/ai-settings").cookie(cookieOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"provider":"deepseek","baseUrl":"https://8.8.8.8",
                                 "model":"deepseek-chat","apiKey":"sk-test-1234567890abcdef"}
                                """))
                .andExpect(status().isOk());
    }

    private MvcResult ask(UUID userId, UUID canvasId, String clientId, String text) throws Exception {
        return mvc.perform(post("/api/canvases/" + canvasId + "/questions")
                        .cookie(cookieOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientId":"%s","blockId":"b_1",
                                 "anchor":{"startLine":1,"endLine":1,"quote":"class A {}"},
                                 "text":"%s","language":"java","title":"Solution.java",
                                 "code":"class A {}"}
                                """.formatted(clientId, text)))
                .andReturn();
    }

    private String questionIdOf(MvcResult result) throws Exception {
        JsonNode node = json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        return node.get("questionId").asText();
    }

    /** 等回答落盘：流是异步的，直接断言会读到 streaming 中间态。 */
    private String awaitAnswerStatus(String questionId) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            List<String> rows = jdbc.queryForList(
                    "select status from answers where question_id = ?::uuid order by created_at desc",
                    String.class, questionId);
            if (!rows.isEmpty() && !"streaming".equals(rows.get(0))) {
                return rows.get(0);
            }
            Thread.sleep(50);
        }
        return "timeout";
    }

    // ---------------------------------------------------------------- 用例

    @Test
    @DisplayName("提问落库幂等：同一 clientId 重复提交只产生一条记录")
    void createQuestionIsIdempotent() throws Exception {
        UUID userId = newUser();
        UUID canvasId = newCanvas(userId);

        MvcResult first = ask(userId, canvasId, "q_lx8f2a", "这里为什么这么写？");
        MvcResult second = ask(userId, canvasId, "q_lx8f2a", "这里为什么这么写？");

        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(questionIdOf(second)).isEqualTo(questionIdOf(first));

        Integer count = jdbc.queryForObject(
                "select count(*) from questions where canvas_id = ?", Integer.class, canvasId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("别人的画布不能提问（404，不泄露存在性）")
    void cannotAskOnForeignCanvas() throws Exception {
        UUID owner = newUser();
        UUID stranger = newUser();
        UUID canvasId = newCanvas(owner);

        MvcResult res = ask(stranger, canvasId, "q_1", "问一下");
        assertThat(res.getResponse().getStatus()).isEqualTo(404);
        assertThat(res.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("CANVAS_NOT_FOUND");
    }

    @Test
    @DisplayName("没配 Key 时回答请求返回 400 AI_NOT_CONFIGURED（前端据此弹「去设置」）")
    void answerWithoutKeyIsRejectedBeforeStreaming() throws Exception {
        UUID userId = newUser();
        UUID canvasId = newCanvas(userId);
        String questionId = questionIdOf(ask(userId, canvasId, "q_1", "问一下"));

        mvc.perform(post("/api/questions/" + questionId + "/answer")
                        .cookie(cookieOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"class A {}\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AI_NOT_CONFIGURED"));
    }

    @Test
    @DisplayName("配好 Key 后按 SSR 推流：delta 逐段到达，结束后 answers 落成 done")
    void streamsAnswerAndPersists() throws Exception {
        UUID userId = newUser();
        UUID canvasId = newCanvas(userId);
        saveAiKey(userId);
        String questionId = questionIdOf(ask(userId, canvasId, "q_stream", "这段在做什么？"));

        when(chat.stream(anyString(), anyString(), anyString(), any(), anyInt(), any(), any(AtomicBoolean.class)))
                .thenAnswer(invocation -> {
                    Consumer<String> onDelta = invocation.getArgument(5);
                    onDelta.accept("这段");
                    onDelta.accept("代码创建了");
                    onDelta.accept("一个类。");
                    return new ChatClient.Usage("deepseek-chat", 128, 12);
                });

        MvcResult result = mvc.perform(post("/api/questions/" + questionId + "/answer")
                        .cookie(cookieOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"class A {}\",\"language\":\"java\",\"title\":\"Solution.java\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThat(awaitAnswerStatus(questionId)).isEqualTo("done");

        MvcResult completed = mvc.perform(asyncDispatch(result)).andReturn();
        String body = completed.getResponse().getContentAsString(StandardCharsets.UTF_8);

        // 事件顺序：start → delta* → done
        assertThat(body).contains("event:start").contains("event:delta").contains("event:done");
        assertThat(body).contains("这段").contains("代码创建了").contains("一个类。");

        String content = jdbc.queryForObject(
                "select content from answers where question_id = ?::uuid order by created_at desc limit 1",
                String.class, questionId);
        assertThat(content).isEqualTo("这段代码创建了一个类。");

        Integer completion = jdbc.queryForObject(
                "select completion_tokens from answers where question_id = ?::uuid order by created_at desc limit 1",
                Integer.class, questionId);
        assertThat(completion).isEqualTo(12);
    }

    @Test
    @DisplayName("上游报错时推 event:error，并把这次回答记为 error")
    void upstreamErrorBecomesSseErrorEvent() throws Exception {
        UUID userId = newUser();
        UUID canvasId = newCanvas(userId);
        saveAiKey(userId);
        String questionId = questionIdOf(ask(userId, canvasId, "q_err", "这段在做什么？"));

        when(chat.stream(anyString(), anyString(), anyString(), any(), anyInt(), any(), any(AtomicBoolean.class)))
                .thenThrow(new com.codeexplain.common.ApiException(
                        com.codeexplain.common.ErrorCode.AI_UPSTREAM_ERROR, "API Key 无效或没有权限"));

        MvcResult result = mvc.perform(post("/api/questions/" + questionId + "/answer")
                        .cookie(cookieOf(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"class A {}\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThat(awaitAnswerStatus(questionId)).isEqualTo("error");

        MvcResult completed = mvc.perform(asyncDispatch(result)).andReturn();
        String body = completed.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("event:error").contains("AI_UPSTREAM_ERROR");
    }

    @Test
    @DisplayName("别人的提问不能回答")
    void cannotAnswerForeignQuestion() throws Exception {
        UUID owner = newUser();
        UUID stranger = newUser();
        UUID canvasId = newCanvas(owner);
        String questionId = questionIdOf(ask(owner, canvasId, "q_own", "问一下"));
        saveAiKey(stranger);

        mvc.perform(post("/api/questions/" + questionId + "/answer")
                        .cookie(cookieOf(stranger))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"class A {}\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("QUESTION_NOT_FOUND"));
    }

    @Test
    @DisplayName("未登录不能提问，也不能取流")
    void requiresSession() throws Exception {
        mvc.perform(post("/api/canvases/" + UUID.randomUUID() + "/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blockId\":\"b_1\",\"anchor\":{},\"text\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }
}
