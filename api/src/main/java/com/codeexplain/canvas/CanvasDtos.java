package com.codeexplain.canvas;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 画布接口的请求/响应体。
 *
 * <p>画布结构不是强类型 DTO，而是整块 {@link JsonNode}：前端要频繁调整布局字段
 * （视口、背景、回答框坐标…），每加一个字段就在 Java 侧开一个 record，会让
 * 「加个样式」变成「改两个仓库 + 一次发版」。jsonb 快照 + 独立的 version 列
 * 才是这个场景合适的建模。
 */
public final class CanvasDtos {

    private CanvasDtos() {
    }

    /** 侧栏列表项：不带 data，避免一次拉回几十个画布的完整代码。 */
    public record CanvasSummary(UUID id,
                                String title,
                                int version,
                                Instant updatedAt,
                                int blockCount,
                                int questionCount) {
    }

    /** 详情：带完整快照。 */
    public record CanvasDetail(UUID id,
                               String title,
                               JsonNode data,
                               int version,
                               Instant createdAt,
                               Instant updatedAt) {
    }

    public record CreateCanvasRequest(@Size(max = 120, message = "画布标题最多 120 个字") String title,
                                      JsonNode data) {
    }

    /**
     * 全量保存。
     *
     * @param baseVersion 客户端基于哪个版本改的；服务端只接受 version 相等时才写入，
     *                    否则说明另一个标签页已经保存过，返回 409 让用户选择覆盖还是刷新
     */
    public record SaveCanvasRequest(@NotBlank(message = "画布标题不能为空")
                                    @Size(max = 120, message = "画布标题最多 120 个字")
                                    String title,
                                    @NotNull(message = "缺少画布数据")
                                    JsonNode data,
                                    @NotNull(message = "缺少版本号")
                                    @Min(value = 0, message = "版本号不能为负")
                                    Integer baseVersion) {
    }
}
