package com.codeexplain.qa;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.codeexplain.ai.AiSettingsService;
import com.codeexplain.ai.ChatClient;
import com.codeexplain.canvas.CanvasRepository;
import com.codeexplain.common.ApiException;
import com.codeexplain.common.ErrorCode;
import com.codeexplain.qa.QaDtos.CreateQuestionRequest;
import com.codeexplain.qa.QaDtos.CreateQuestionResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 提问与流式回答。
 *
 * <p>为什么用 {@link SseEmitter} + 虚拟线程，而不是 WebFlux：整个应用是 MVC 的（画布接口是
 * 普通阻塞 JDBC），为了一个 SSE 端点把线程模型换成响应式，等于让其余所有代码都跟着改。
 * JDK 21 的虚拟线程让「一个请求一个线程」的成本降到可以忽略，阻塞式写法反而更简单。
 *
 * <p>事件协议（前端按 event 名分支）：
 * <pre>
 * event: start   data: {"answerId":"...","model":"..."}
 * event: delta   data: {"text":"增量"}          ← 可能几十到几百次
 * event: done    data: {"model":"...","promptTokens":123,"completionTokens":456}
 * event: error   data: {"code":"AI_UPSTREAM_ERROR","message":"..."}
 * </pre>
 */
@Service
public class QaService {

    private static final Logger log = LoggerFactory.getLogger(QaService.class);

    /** 平台（Vercel）单次请求上限 300s，这里留足余量：超过 3 分钟的回答用户也不会看。 */
    private static final long STREAM_TIMEOUT_MS = 180_000L;

    private static final int MAX_TOKENS = 900;

    /** 每积累这么多次增量就落一次库，避免每来一个字都写一次数据库。 */
    private static final int FLUSH_EVERY_DELTAS = 40;

    private final QuestionRepository questions;
    private final AnswerRepository answers;
    private final CanvasRepository canvases;
    private final AiSettingsService settings;
    private final ChatClient chat;
    private final ObjectMapper json;

    public QaService(QuestionRepository questions, AnswerRepository answers, CanvasRepository canvases,
                     AiSettingsService settings, ChatClient chat, ObjectMapper json) {
        this.questions = questions;
        this.answers = answers;
        this.canvases = canvases;
        this.settings = settings;
        this.chat = chat;
        this.json = json;
    }

    /** 落一条提问记录（幂等）。画布归属先校验，越权与不存在都回 404。 */
    public CreateQuestionResponse createQuestion(UUID userId, UUID canvasId, CreateQuestionRequest req) {
        canvases.find(canvasId, userId).orElseThrow(() -> new ApiException(ErrorCode.CANVAS_NOT_FOUND));

        AnchorView anchor = AnchorView.parse(req.anchor());
        if (anchor.quote().isBlank()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "选中片段为空", "anchor");
        }
        // clientId 只是「幂等键」，缺失也能存（历史数据/内部写入允许为空）
        UUID id = questions.upsert(canvasId, blankToNull(req.clientId()), req.blockId(),
                req.anchor().toString(), req.text().trim());
        return new CreateQuestionResponse(id, req.clientId());
    }

    /**
     * 发起一次流式回答。
     *
     * <p>所有「会失败且用户能处理」的检查（未登录、问题不存在、没配 Key）都在返回
     * SseEmitter <b>之前</b>做完：那时还能返回一个正常的 JSON 400/404，
     * 前端按 code 弹提示即可。一旦开始推流，就只能用 event: error 传达了。
     *
     * @param code 代码块当前内容。为空时回落到画布快照里的版本（见 {@link #resolveCode}）
     */
    public SseEmitter streamAnswer(UUID userId, UUID questionId,
                                   String code, String language, String title) {
        var question = questions.findOwned(questionId, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.QUESTION_NOT_FOUND));
        // 没配 Key 在这里抛 400 AI_NOT_CONFIGURED，前端据此弹出「去设置」
        var resolved = settings.resolve(userId);

        CodeContext context = resolveCode(userId, question, code, language, title);

        UUID answerId = answers.insert(questionId, resolved.provider(), resolved.label());
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        AtomicBoolean cancelled = new AtomicBoolean(false);

        // 前端关页面 / 断网 / 超时：都要尽快停止上游请求，否则白花钱
        emitter.onCompletion(() -> cancelled.set(true));
        emitter.onTimeout(() -> cancelled.set(true));
        emitter.onError(e -> cancelled.set(true));

        Thread.ofVirtual().name("ai-" + answerId).start(
                () -> run(emitter, question, context, resolved, answerId, cancelled));
        return emitter;
    }

    private void run(SseEmitter emitter, QuestionRepository.Row question, CodeContext context,
                     AiSettingsService.Resolved resolved, UUID answerId, AtomicBoolean cancelled) {
        StringBuilder content = new StringBuilder();
        int[] deltaCount = {0};
        AnchorView anchor = AnchorView.parse(readJson(question.anchorJson()));

        try {
            send(emitter, "start", Map.of("answerId", answerId.toString(), "model", resolved.label()));

            var messages = ExplainPrompt.messages(context.language(), context.title(), context.code(),
                    anchor.startLine(), anchor.endLine(), anchor.quote(), question.text());

            var usage = chat.stream(resolved.baseUrl(), resolved.apiKey(), resolved.model(), messages,
                    MAX_TOKENS,
                    delta -> {
                        content.append(delta);
                        deltaCount[0]++;
                        // 定期落库：容器被平台掐掉时，用户至少还能看到半段答案
                        if (deltaCount[0] % FLUSH_EVERY_DELTAS == 0) {
                            answers.updateContent(answerId, content.toString());
                        }
                        try {
                            send(emitter, "delta", Map.of("text", delta));
                        } catch (RuntimeException e) {
                            // 发送失败说明连接已断，让 while 循环里的 cancelled 生效
                            cancelled.set(true);
                        }
                    },
                    cancelled);

            answers.finish(answerId, content.toString(), usage.model(),
                    usage.promptTokens(), usage.completionTokens());

            Map<String, Object> done = new LinkedHashMap<>();
            done.put("model", resolved.label());
            done.put("promptTokens", usage.promptTokens());
            done.put("completionTokens", usage.completionTokens());
            send(emitter, "done", done);
            emitter.complete();
        } catch (StreamClosedException e) {
            // 用户自己关了页面：不算失败，把已生成的内容存下来就行
            log.debug("客户端已断开（answer={}），保存已生成内容", answerId);
            answers.updateContent(answerId, content.toString());
            emitter.complete();
        } catch (ApiException e) {
            log.warn("回答失败（question={}）：{}", question.id(), e.getMessage());
            answers.fail(answerId, content.toString(), e.getMessage());
            sendQuietly(emitter, "error", Map.of("code", e.code().name(), "message", e.getMessage()));
            emitter.complete();
        } catch (Exception e) {
            log.error("回答异常（question={}）", question.id(), e);
            answers.fail(answerId, content.toString(), "服务器异常：" + e.getClass().getSimpleName());
            sendQuietly(emitter, "error", Map.of(
                    "code", ErrorCode.INTERNAL.name(),
                    "message", "生成回答时出错，请稍后重试"));
            emitter.complete();
        }
    }

    /**
     * 取这次要喂给模型的代码。
     *
     * <p>优先用前端传来的当前内容：快照是 700ms 防抖保存的，刚粘完代码立刻提问时
     * 快照里可能还没有这段代码，模型会拿到空上下文然后胡编。
     * 前端没传（例如重答历史问答）才回落到快照里的版本。
     */
    private CodeContext resolveCode(UUID userId, QuestionRepository.Row question,
                                    String code, String language, String title) {
        if (code != null && !code.isBlank()) {
            return new CodeContext(code, language, title);
        }
        return canvases.find(question.canvasId(), userId)
                .map(row -> {
                    JsonNode blocks = row.data().path("blocks");
                    for (JsonNode block : blocks) {
                        if (question.blockId().equals(block.path("id").asText())) {
                            return new CodeContext(
                                    block.path("content").asText(""),
                                    block.path("language").asText(""),
                                    block.path("title").asText(""));
                        }
                    }
                    return new CodeContext("", language, title);
                })
                .orElseGet(() -> new CodeContext("", language, title));
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event)
                    .data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            // 连接已断：抛出去只能变成日志噪音，交给 cancelled 收尾
            throw new StreamClosedException(e);
        }
    }

    private void sendQuietly(SseEmitter emitter, String event, Object data) {
        try {
            send(emitter, event, data);
        } catch (RuntimeException ignored) {
            // 连不上就算了，此时前端已经不在
        }
    }

    private JsonNode readJson(String raw) {
        try {
            return json.readTree(raw);
        } catch (Exception e) {
            return json.createObjectNode();
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 只是把「发送失败」与真正业务异常区分开，避免落库时把断线记成 AI 出错。 */
    private static final class StreamClosedException extends RuntimeException {
        StreamClosedException(Throwable cause) {
            super(cause);
        }
    }

    record CodeContext(String code, String language, String title) {
    }

    /** 锚点里与提示词相关的字段。缺字段一律给安全默认值，坏数据不该让请求 500。 */
    record AnchorView(int startLine, int endLine, String quote) {

        static AnchorView parse(JsonNode node) {
            int startLine = node.path("startLine").asInt(1);
            int endLine = node.path("endLine").asInt(startLine);
            String quote = node.path("quote").asText("");
            return new AnchorView(Math.max(1, startLine), Math.max(startLine, endLine), quote);
        }
    }
}
