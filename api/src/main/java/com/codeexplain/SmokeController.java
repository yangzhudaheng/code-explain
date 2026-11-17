package com.codeexplain;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * M0.5 冒烟测试端点。
 * 目的：验证 Vercel 容器能否启动、冷启动耗时、以及 SSE 长连接能撑多久。
 * 业务上线后这三个端点可以删掉。
 */
@RestController
@RequestMapping("/api")
public class SmokeController {

    private final Instant bootedAt = Instant.now();

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        return Map.of(
                "ok", true,
                "app", "code-explain-api",
                "uptimeSeconds", Duration.between(bootedAt, Instant.now()).toSeconds(),
                "port", System.getenv().getOrDefault("PORT", "8080"),
                "java", System.getProperty("java.version")
        );
    }

    /**
     * SSE 压测：默认每 2 秒推一条，共 10 条（约 20 秒）。
     * 若 Vercel 在此处提前断开，说明流式回答需要降级为「一次性返回」。
     */
    @GetMapping(value = "/dev/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(defaultValue = "10") int count,
                             @RequestParam(defaultValue = "2000") long intervalMs) {
        SseEmitter emitter = new SseEmitter(120_000L);
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        Instant start = Instant.now();

        scheduler.scheduleAtFixedRate(() -> {
            try {
                long elapsed = Duration.between(start, Instant.now()).toMillis();
                emitter.send(SseEmitter.event()
                        .name("delta")
                        .data("chunk at " + elapsed + "ms"));
            } catch (Exception e) {
                emitter.completeWithError(e);
                scheduler.shutdown();
            }
        }, 0, intervalMs, TimeUnit.MILLISECONDS);

        scheduler.schedule(() -> {
            try {
                emitter.send(SseEmitter.event().name("done").data("ok"));
                emitter.complete();
            } catch (Exception ignored) {
                // 连接已断开
            }
            scheduler.shutdown();
        }, intervalMs * (count + 1), TimeUnit.MILLISECONDS);

        emitter.onCompletion(scheduler::shutdown);
        emitter.onTimeout(scheduler::shutdown);
        return emitter;
    }
}
