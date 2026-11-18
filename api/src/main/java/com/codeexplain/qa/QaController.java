package com.codeexplain.qa;

import java.util.UUID;

import com.codeexplain.auth.AuthInterceptor;
import com.codeexplain.qa.QaDtos.AnswerRequest;
import com.codeexplain.qa.QaDtos.CreateQuestionRequest;
import com.codeexplain.qa.QaDtos.CreateQuestionResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 问答接口：落一条提问记录，然后按 SSE 推进回答。 */
@RestController
@RequestMapping("/api")
public class QaController {

    private final QaService qa;

    public QaController(QaService qa) {
        this.qa = qa;
    }

    @PostMapping("/canvases/{canvasId}/questions")
    public CreateQuestionResponse ask(@RequestAttribute(AuthInterceptor.USER_ID) UUID userId,
                                      @PathVariable UUID canvasId,
                                      @Valid @RequestBody CreateQuestionRequest body) {
        return qa.createQuestion(userId, canvasId, body);
    }

    /**
     * 开始生成回答（每次调用都是一次新的尝试，历史保留在 answers 表）。
     *
     * <p>{@code produces = text/event-stream} 是必须的：否则 Spring 会按 Accept 协商成 JSON，
     * 前端拿到的就不是流了。未开始推流前的错误（未登录 / 没配 Key）仍是普通 JSON，
     * 所以前端要先看响应头再决定怎么读。
     */
    @PostMapping(value = "/questions/{questionId}/answer", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter answer(@RequestAttribute(AuthInterceptor.USER_ID) UUID userId,
                             @PathVariable UUID questionId,
                             @RequestBody(required = false) AnswerRequest body) {
        AnswerRequest req = body == null ? new AnswerRequest(null, null, null) : body;
        return qa.streamAnswer(userId, questionId, req.code(), req.language(), req.title());
    }
}
