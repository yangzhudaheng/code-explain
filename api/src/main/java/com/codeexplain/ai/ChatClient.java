package com.codeexplain.ai;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.codeexplain.common.ApiException;
import com.codeexplain.common.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * OpenAI 兼容的 chat/completions 客户端（只用 JDK 自带的 HttpClient）。
 *
 * <p>为什么不用 WebFlux / Spring AI：本项目只有一个上游、一种协议（SSE 流），
 * 引 WebFlux 会让整个应用的线程模型从 MVC 变成响应式，画布接口跟着要重写；
 * Spring AI 则把「哪家 Provider、哪个模型」封装成需要跟着版本走的抽象，
 * 而我们只需要一个 POST 和一个按行读的流。
 *
 * <p>SSE 的解析必须按行做：一个事件可能被 TCP 拆成几段到达，也可能一次到达多个事件。
 * 用 {@code BufferedReader.readLine()} 天然按事件边界切分，比拼包安全。
 */
@Component
public class ChatClient {

    private static final Logger log = LoggerFactory.getLogger(ChatClient.class);

    private static final int MAX_ERROR_BODY = 400;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            // 不自动跟随重定向：跳转目标可能是不受 SSRF 校验约束的内网地址
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final ObjectMapper json;

    public ChatClient(ObjectMapper json) {
        this.json = json;
    }

    public record Message(String role, String content) {
    }

    /** 流式结果：模型名与 token 用量（有些服务不在流里返回用量，此时为 null）。 */
    public record Usage(String model, Integer promptTokens, Integer completionTokens) {
    }

    /**
     * 发起流式对话，边收边回调。
     *
     * @param cancelled 前端断开时置 true，避免为一个没人看的回答继续计费
     * @throws ApiException 上游拒绝（Key 无效、余额不足、限流等）时抛出，文案已面向用户
     */
    public Usage stream(String baseUrl, String apiKey, String model, List<Message> messages,
                        int maxTokens, Consumer<String> onDelta, AtomicBoolean cancelled) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages.stream()
                .map(m -> Map.of("role", m.role(), "content", m.content()))
                .toList());
        body.put("stream", true);
        body.put("temperature", 0.3);
        body.put("max_tokens", maxTokens);
        // 让服务端在最后一帧带上 token 用量（DeepSeek / OpenAI 均支持；不支持的服务会忽略）
        body.put("stream_options", Map.of("include_usage", true));

        HttpRequest request = requestBuilder(baseUrl + "/chat/completions", apiKey)
                // 首字节超时：模型排队时可能几十秒没响应，给足但不无限等
                .timeout(Duration.ofSeconds(90))
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(body), StandardCharsets.UTF_8))
                .build();

        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (java.net.http.HttpTimeoutException e) {
            throw new ApiException(ErrorCode.AI_UPSTREAM_ERROR, "模型响应超时，请稍后重试");
        } catch (Exception e) {
            throw new ApiException(ErrorCode.AI_UPSTREAM_ERROR, "无法连接模型服务：" + e.getMessage());
        }

        if (response.statusCode() != 200) {
            String detail = readBody(response.body());
            throw upstreamError(response.statusCode(), detail);
        }

        String resolvedModel = model;
        Integer promptTokens = null;
        Integer completionTokens = null;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (cancelled.get()) {
                    log.debug("前端已断开，中止上游流");
                    break;
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                String payload = line.substring(5).trim();
                if (payload.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(payload)) {
                    break;
                }
                JsonNode node = json.readTree(payload);
                if (node.hasNonNull("model")) {
                    resolvedModel = node.get("model").asText(resolvedModel);
                }
                if (node.has("usage") && !node.get("usage").isNull()) {
                    JsonNode usage = node.get("usage");
                    promptTokens = usage.hasNonNull("prompt_tokens") ? usage.get("prompt_tokens").asInt() : promptTokens;
                    completionTokens = usage.hasNonNull("completion_tokens")
                            ? usage.get("completion_tokens").asInt() : completionTokens;
                }
                String delta = deltaText(node);
                if (!delta.isEmpty()) {
                    onDelta.accept(delta);
                }
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            // 已经吐出去的内容不回滚：前端保留半段回答，比整段消失体验好
            throw new ApiException(ErrorCode.AI_UPSTREAM_ERROR, "读取模型响应中断：" + e.getMessage());
        }
        return new Usage(resolvedModel, promptTokens, completionTokens);
    }

    /**
     * 探活：非流式发一条最短请求，验证 Key、地址、模型名是否都对。
     *
     * @return 往返毫秒数
     */
    public long probe(String baseUrl, String apiKey, String model) {
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", List.of(Map.of("role", "user", "content", "ping")),
                "max_tokens", 1,
                "temperature", 0);

        HttpRequest request = requestBuilder(baseUrl + "/chat/completions", apiKey)
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(body), StandardCharsets.UTF_8))
                .build();

        long started = System.nanoTime();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw upstreamError(response.statusCode(), response.body());
            }
            return (System.nanoTime() - started) / 1_000_000;
        } catch (ApiException e) {
            throw e;
        } catch (java.net.http.HttpTimeoutException e) {
            throw new ApiException(ErrorCode.AI_UPSTREAM_ERROR, "连接超时，请检查模型地址");
        } catch (Exception e) {
            throw new ApiException(ErrorCode.AI_UPSTREAM_ERROR, "无法连接模型服务：" + e.getMessage());
        }
    }

    private HttpRequest.Builder requestBuilder(String url, String apiKey) {
        try {
            return HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey);
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.BASE_URL_FORBIDDEN, "模型地址不合法");
        }
    }

    /** 取增量文本：兼容 content 与部分服务（如深度思考模型）的 reasoning_content。 */
    private String deltaText(JsonNode node) {
        JsonNode choices = node.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            return "";
        }
        JsonNode delta = choices.get(0).get("delta");
        if (delta == null) {
            return "";
        }
        if (delta.hasNonNull("content")) {
            return delta.get("content").asText();
        }
        return "";
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化请求体失败", e);
        }
    }

    private String readBody(InputStream stream) {
        try (InputStream in = stream) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return text.length() > MAX_ERROR_BODY ? text.substring(0, MAX_ERROR_BODY) : text;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 把上游 HTTP 错误翻译成用户能照做的动作。
     *
     * <p>把 401/402/429 的原始报文直接丢给用户没有意义（一堆英文与 json），
     * 但这几类的处理方式完全不同：换 Key、充钱、等一会。
     */
    private ApiException upstreamError(int status, String body) {
        log.warn("模型服务返回 {}：{}", status, body);
        String detail = providerMessage(body);
        return switch (status) {
            case 401, 403 -> new ApiException(ErrorCode.AI_UPSTREAM_ERROR,
                    "API Key 无效或没有权限，请到「AI 设置」里重新填写" + detail);
            case 402 -> new ApiException(ErrorCode.AI_UPSTREAM_ERROR,
                    "模型账户余额不足，请到服务商后台充值");
            case 404 -> new ApiException(ErrorCode.AI_UPSTREAM_ERROR,
                    "模型地址或模型名不存在，请检查 base-url 与模型名" + detail);
            case 429 -> new ApiException(ErrorCode.AI_RATE_LIMITED);
            case 400 -> new ApiException(ErrorCode.AI_UPSTREAM_ERROR,
                    "模型服务拒绝了这次请求" + detail);
            default -> new ApiException(ErrorCode.AI_UPSTREAM_ERROR,
                    "模型服务返回 " + status + detail);
        };
    }

    /** 从错误体里抠出 message 字段，抠不到就原样截断。 */
    private String providerMessage(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            JsonNode node = json.readTree(body);
            JsonNode message = node.path("error").path("message");
            if (message.isTextual()) {
                return "：" + message.asText();
            }
        } catch (Exception ignored) {
            // 不是 json（比如网关返回的 HTML），走下面的原样截断
        }
        return "：" + body.trim();
    }

    /** 供测试构造消息列表用，避免测试里到处写 Map.of。 */
    public static List<Message> conversation(String system, String user) {
        List<Message> messages = new ArrayList<>(2);
        messages.add(new Message("system", system));
        messages.add(new Message("user", user));
        return messages;
    }
}
