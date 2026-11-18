package com.codeexplain.qa;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 问答接口的请求 / 响应体。 */
public final class QaDtos {

    private QaDtos() {
    }

    /**
     * @param clientId 前端生成的 {@code q_xxx}，用于把这条记录和画布快照里的问答对上
     * @param anchor   语义锚点（像素无关），原样存 jsonb
     * @param code     代码块当前内容。<b>由前端提供而不是从快照读</b>：快照是 700ms 防抖保存的，
     *                 刚粘完代码立刻提问时快照里可能还没有这段代码，模型会拿到空上下文
     */
    public record CreateQuestionRequest(@Size(max = 64) String clientId,
                                        @NotBlank @Size(max = 64) String blockId,
                                        @NotNull JsonNode anchor,
                                        @NotBlank @Size(max = 2000) String text,
                                        @Size(max = 40) String language,
                                        @Size(max = 120) String title,
                                        @Size(max = 40000) String code) {
    }

    public record CreateQuestionResponse(UUID questionId, String clientId) {
    }

    /**
     * 发起回答时随请求带上代码块当前内容。
     *
     * <p>原本设计成 {@code GET /api/questions/{id}/answer}（无请求体），实现时发现这样拿不到
     * 「用户此刻屏幕上的代码」——快照有 700ms 防抖，刚粘完代码立刻提问时快照里还没有它。
     * 改成 POST 带 code：既能保证模型看到的是正确内容，也让「改完代码重新回答」天然成立。
     *
     * @param code 为空时回落到画布快照里的同名代码块
     */
    public record AnswerRequest(@Size(max = 40000) String code,
                                @Size(max = 40) String language,
                                @Size(max = 120) String title) {
    }
}
