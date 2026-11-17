package com.codeexplain;

import java.time.Instant;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 域名根路径的健康页。
 *
 * <p>后端只提供 JSON 接口、没有页面。若不映射 {@code /}，访问域名根路径会得到 404
 * —— HTTP 语义没错，但刚部署完的人打开域名看到一片报错，第一反应是「部署失败了」。
 * 这里给一段自述，既是给人看的「活着」信号，也方便探活工具直接打根路径。
 */
@RestController
public class ServiceInfoController {

    @GetMapping(value = "/", produces = "application/json;charset=UTF-8")
    public Map<String, Object> root() {
        return Map.of(
                "service", "code-explain-api",
                "status", "running",
                "time", Instant.now().toString(),
                "endpoints", Map.of(
                        "ping", "/api/ping",
                        "db", "/api/db/ping",
                        "stream", "/api/dev/stream"
                )
        );
    }
}
