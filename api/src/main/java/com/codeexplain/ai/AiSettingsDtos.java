package com.codeexplain.ai;

import java.util.List;

import jakarta.validation.constraints.Size;

/** 模型设置的请求 / 响应体。任何响应都<b>不含</b> API Key 明文，只有掩码。 */
public final class AiSettingsDtos {

    private AiSettingsDtos() {
    }

    /**
     * @param hasKey              是否已保存过 Key（前端据此决定显示「已配置」还是「去填写」）
     * @param keyEncryptionReady  服务端是否配了 APP_ENC_KEY；false 时保存 Key 会失败，要提前告知
     */
    public record AiSettingsResponse(String provider,
                                     String baseUrl,
                                     String model,
                                     String apiKeyMask,
                                     boolean hasKey,
                                     boolean keyEncryptionReady,
                                     List<PresetDto> presets) {
    }

    public record PresetDto(String id, String label, String baseUrl, String model, List<String> models) {
        static PresetDto from(AiPresets.AiPreset preset) {
            return new PresetDto(preset.id(), preset.label(), preset.baseUrl(), preset.model(), preset.models());
        }
    }

    /**
     * @param apiKey   留空表示「不修改已有 Key」；想换 Key 才填
     * @param clearKey 显式清除 Key（与 apiKey 同时给时以 clearKey 为准）
     */
    public record SaveRequest(@Size(max = 32) String provider,
                              @Size(max = 300) String baseUrl,
                              @Size(max = 100) String model,
                              @Size(max = 200) String apiKey,
                              boolean clearKey) {
    }

    public record TestResponse(boolean ok, long latencyMs, String model, String error) {
    }
}
