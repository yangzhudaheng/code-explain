package com.codeexplain.ai;

import java.util.UUID;

import com.codeexplain.ai.AiSettingsDtos.AiSettingsResponse;
import com.codeexplain.ai.AiSettingsDtos.SaveRequest;
import com.codeexplain.ai.AiSettingsDtos.TestResponse;
import com.codeexplain.auth.AuthInterceptor;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前账号的模型配置。
 *
 * <p>路径挂在 {@code /api/me} 下：它属于「人」而不是「某个画布」，
 * 换画布不需要重填 Key，也不该跟着画布一起删。
 */
@RestController
@RequestMapping("/api/me/ai-settings")
public class AiSettingsController {

    private final AiSettingsService settings;

    public AiSettingsController(AiSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    public AiSettingsResponse get(@RequestAttribute(AuthInterceptor.USER_ID) UUID userId) {
        return settings.get(userId);
    }

    @PutMapping
    public AiSettingsResponse save(@RequestAttribute(AuthInterceptor.USER_ID) UUID userId,
                                   @Valid @RequestBody SaveRequest body) {
        return settings.save(userId, body);
    }

    /** 测试已保存的配置。故意不接收 body：要验证的必须是「真正会被用来回答的那份配置」。 */
    @PostMapping("/test")
    public TestResponse test(@RequestAttribute(AuthInterceptor.USER_ID) UUID userId) {
        return settings.test(userId);
    }
}
