package com.codeexplain.canvas;

import java.util.List;
import java.util.UUID;

import com.codeexplain.auth.AuthInterceptor;
import com.codeexplain.canvas.CanvasDtos.CanvasDetail;
import com.codeexplain.canvas.CanvasDtos.CanvasSummary;
import com.codeexplain.canvas.CanvasDtos.CreateCanvasRequest;
import com.codeexplain.canvas.CanvasDtos.SaveCanvasRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 画布接口，全部要求登录（拦截器已把 {@code userId} 放进请求属性）。
 *
 * <p>保存用 PUT 全量而非 PATCH 增量：画布是典型的「小块高频改」，增量协议要维护
 * 一套 diff/patch 语义，收益远小于把一个 200KB 的 jsonb 整体覆盖。
 */
@RestController
@RequestMapping("/api/canvases")
public class CanvasController {

    private final CanvasService canvases;

    public CanvasController(CanvasService canvases) {
        this.canvases = canvases;
    }

    @GetMapping
    public List<CanvasSummary> list(@RequestAttribute(AuthInterceptor.USER_ID) UUID userId) {
        return canvases.list(userId);
    }

    @GetMapping("/{id}")
    public CanvasDetail get(@RequestAttribute(AuthInterceptor.USER_ID) UUID userId,
                            @PathVariable UUID id) {
        return canvases.get(userId, id);
    }

    @PostMapping
    public ResponseEntity<CanvasDetail> create(
            @RequestAttribute(AuthInterceptor.USER_ID) UUID userId,
            @Valid @RequestBody(required = false) CreateCanvasRequest body) {
        CanvasDetail created = canvases.create(userId, body);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    public CanvasDetail save(@RequestAttribute(AuthInterceptor.USER_ID) UUID userId,
                             @PathVariable UUID id,
                             @Valid @RequestBody SaveCanvasRequest body) {
        return canvases.save(userId, id, body);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@RequestAttribute(AuthInterceptor.USER_ID) UUID userId,
                                       @PathVariable UUID id) {
        canvases.delete(userId, id);
        return ResponseEntity.noContent().build();
    }
}
