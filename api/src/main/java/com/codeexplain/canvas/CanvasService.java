package com.codeexplain.canvas;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import com.codeexplain.canvas.CanvasDtos.CanvasDetail;
import com.codeexplain.canvas.CanvasDtos.CanvasSummary;
import com.codeexplain.canvas.CanvasDtos.CreateCanvasRequest;
import com.codeexplain.canvas.CanvasDtos.SaveCanvasRequest;
import com.codeexplain.common.ApiException;
import com.codeexplain.common.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 画布业务规则：归属、数量上限、快照大小、乐观锁。 */
@Service
public class CanvasService {

    private static final String DEFAULT_TITLE = "未命名画布";

    private final CanvasRepository repo;
    private final ObjectMapper json;
    private final int maxPerUser;
    private final int maxDataBytes;

    public CanvasService(CanvasRepository repo, ObjectMapper json,
                         @Value("${app.canvas.max-per-user:50}") int maxPerUser,
                         @Value("${app.canvas.max-data-bytes:2097152}") int maxDataBytes) {
        this.repo = repo;
        this.json = json;
        this.maxPerUser = maxPerUser;
        this.maxDataBytes = maxDataBytes;
    }

    public List<CanvasSummary> list(UUID userId) {
        return repo.listByUser(userId);
    }

    public CanvasDetail get(UUID userId, UUID id) {
        return repo.find(id, userId)
                .map(CanvasService::toDetail)
                .orElseThrow(() -> new ApiException(ErrorCode.CANVAS_NOT_FOUND));
    }

    public CanvasDetail create(UUID userId, CreateCanvasRequest req) {
        // 上限防止「刷一个账号把库塞满」；50 个画布对正常使用足够
        if (repo.countByUser(userId) >= maxPerUser) {
            throw new ApiException(ErrorCode.CANVAS_LIMIT_REACHED,
                    "最多只能有 " + maxPerUser + " 个画布，请先删除不用的画布");
        }
        String title = title(req == null ? null : req.title(), DEFAULT_TITLE);
        String data = serialize(req == null || req.data() == null ? emptyDoc() : req.data());
        UUID id = repo.insert(userId, title, data);
        return get(userId, id);
    }

    public CanvasDetail save(UUID userId, UUID id, SaveCanvasRequest req) {
        // 先取一次：既拿默认标题，也让「id 不属于我」和「版本冲突」能返回不同错误码
        CanvasRepository.CanvasRow existing = repo.find(id, userId)
                .orElseThrow(() -> new ApiException(ErrorCode.CANVAS_NOT_FOUND));

        String data = serialize(req.data());
        String title = title(req.title(), existing.title());

        if (repo.update(id, userId, req.baseVersion(), title, data) == 0) {
            // 0 行只有两种可能：被人删了，或版本被别人推进了
            repo.find(id, userId).orElseThrow(() -> new ApiException(ErrorCode.CANVAS_NOT_FOUND));
            throw new ApiException(ErrorCode.CANVAS_VERSION_CONFLICT);
        }
        return get(userId, id);
    }

    public void delete(UUID userId, UUID id) {
        if (repo.softDelete(id, userId) == 0) {
            throw new ApiException(ErrorCode.CANVAS_NOT_FOUND);
        }
    }

    /** 空白画布也给完整骨架：任何客户端拿到的都必须能直接渲染。 */
    private ObjectNode emptyDoc() {
        ObjectNode doc = json.createObjectNode();
        ObjectNode viewport = doc.putObject("viewport");
        viewport.put("x", 120);
        viewport.put("y", 120);
        viewport.put("zoom", 1);
        doc.putArray("blocks");
        doc.putArray("questions");
        ObjectNode bg = doc.putObject("bg");
        bg.put("pattern", "dot");
        bg.put("tone", "warm");
        return doc;
    }

    private String title(String raw, String fallback) {
        String t = raw == null ? "" : raw.trim();
        return t.isEmpty() ? fallback : t;
    }

    /**
     * 序列化并卡大小。
     *
     * <p>限制在业务层而不是 Spring 的 max-request-size：后者会先读进内存再拒绝，
     * 而这里至少能在解析成 JSON 树之后给出明确的 {@code CANVAS_TOO_LARGE}，
     * 前端才能提示「内容太大」而不是一个 400。
     */
    private String serialize(JsonNode data) {
        if (data == null || !data.isObject()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "画布数据必须是 JSON 对象", "data");
        }
        String text;
        try {
            text = json.writeValueAsString(data);
        } catch (Exception e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "画布数据无法序列化", "data");
        }
        if (text.getBytes(StandardCharsets.UTF_8).length > maxDataBytes) {
            throw new ApiException(ErrorCode.CANVAS_TOO_LARGE,
                    "画布内容超过 " + (maxDataBytes / 1024 / 1024) + "MB，请删掉一些代码块再保存");
        }
        return text;
    }

    private static CanvasDetail toDetail(CanvasRepository.CanvasRow row) {
        return new CanvasDetail(row.id(), row.title(), row.data(), row.version(),
                row.createdAt(), row.updatedAt());
    }
}
