package com.codeexplain.ai;

import java.util.List;
import java.util.UUID;

import com.codeexplain.ai.AiSettingsDtos.AiSettingsResponse;
import com.codeexplain.ai.AiSettingsDtos.PresetDto;
import com.codeexplain.ai.AiSettingsDtos.SaveRequest;
import com.codeexplain.ai.AiSettingsDtos.TestResponse;
import com.codeexplain.common.ApiException;
import com.codeexplain.common.ErrorCode;
import org.springframework.stereotype.Service;

/**
 * 用户自带模型配置的读写与连通性测试。
 *
 * <p>三条贯穿始终的规则：
 * <ol>
 *   <li>Key 只进不出：接口永远只回掩码，解密只发生在真正调用模型的那一刻；</li>
 *   <li>base-url 一律过 {@link BaseUrlGuard}，用户能填 URL 就等于给了他一个内网探测器；</li>
 *   <li>没配 APP_ENC_KEY 时拒绝保存，绝不退化成明文——那是把用户的付费凭据写进库里。</li>
 * </ol>
 */
@Service
public class AiSettingsService {

    private final AiSettingsRepository repo;
    private final SecretBox secretBox;
    private final BaseUrlGuard urlGuard;
    private final ChatClient chat;

    public AiSettingsService(AiSettingsRepository repo, SecretBox secretBox,
                             BaseUrlGuard urlGuard, ChatClient chat) {
        this.repo = repo;
        this.secretBox = secretBox;
        this.urlGuard = urlGuard;
        this.chat = chat;
    }

    public AiSettingsResponse get(UUID userId) {
        var presets = AiPresets.ALL.stream().map(PresetDto::from).toList();
        return repo.find(userId)
                .map(row -> new AiSettingsResponse(
                        row.provider(), row.baseUrl(), row.model(), row.apiKeyMask(),
                        row.apiKeyCipher() != null, secretBox.configured(), presets))
                // 没配置过也要回预设，否则前端拿不到默认值，用户得自己背 base-url
                .orElseGet(() -> new AiSettingsResponse(
                        AiPresets.DEFAULT.id(), AiPresets.DEFAULT.baseUrl(), AiPresets.DEFAULT.model(),
                        null, false, secretBox.configured(), presets));
    }

    public AiSettingsResponse save(UUID userId, SaveRequest req) {
        var preset = AiPresets.byId(req.provider() == null || req.provider().isBlank()
                ? AiPresets.DEFAULT.id() : req.provider());

        String baseUrl = urlGuard.normalize(req.baseUrl() == null || req.baseUrl().isBlank()
                ? preset.baseUrl() : req.baseUrl());
        String model = req.model() == null || req.model().isBlank() ? preset.model() : req.model().trim();

        if (req.clearKey()) {
            repo.clearKey(userId);
            // 保留地址与模型，只清 Key：用户可能只是想换一把新 Key，不想重填其他字段
            repo.upsert(userId, preset.id(), baseUrl, model, null, null);
            return get(userId);
        }

        String apiKey = req.apiKey() == null ? "" : req.apiKey().trim();
        if (apiKey.isEmpty()) {
            repo.upsert(userId, preset.id(), baseUrl, model, null, null);
            return get(userId);
        }

        // 用户常把 "Bearer xxx" 整段粘进来，只留 token 部分
        if (apiKey.regionMatches(true, 0, "bearer ", 0, 7)) {
            apiKey = apiKey.substring(7).trim();
        }
        if (!secretBox.configured()) {
            throw new ApiException(ErrorCode.INTERNAL,
                    "服务端未配置 APP_ENC_KEY，无法安全保存 API Key（请先按 README 生成密钥并重启）");
        }
        repo.upsert(userId, preset.id(), baseUrl, model,
                secretBox.encrypt(apiKey), SecretBox.mask(apiKey));
        return get(userId);
    }

    /**
     * 探活：用已保存的配置发一条最短请求。
     *
     * <p>失败不抛异常而是回 {@code ok:false}：这是「测试」按钮，用户要的是原因，
     * 不是又一条红色错误码。
     */
    public TestResponse test(UUID userId) {
        try {
            var resolved = resolve(userId);
            long latency = chat.probe(resolved.baseUrl(), resolved.apiKey(), resolved.model());
            return new TestResponse(true, latency, resolved.model(), null);
        } catch (ApiException e) {
            return new TestResponse(false, 0, null, e.getMessage());
        } catch (Exception e) {
            return new TestResponse(false, 0, null, e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /** 调用模型前取用：包含解密后的 Key，只应存在于本次请求的内存里。 */
    public Resolved resolve(UUID userId) {
        var row = repo.find(userId)
                .orElseThrow(() -> new ApiException(ErrorCode.AI_NOT_CONFIGURED));
        if (row.apiKeyCipher() == null) {
            throw new ApiException(ErrorCode.AI_NOT_CONFIGURED);
        }
        return new Resolved(row.provider(), row.baseUrl(), row.model(),
                secretBox.decrypt(row.apiKeyCipher()));
    }

    public record Resolved(String provider, String baseUrl, String model, String apiKey) {
        /** 记录到 answers.model 时用「provider/model」，方便审计时看出是哪家。 */
        public String label() {
            return provider + "/" + model;
        }
    }

    /** 测试用：返回预设列表，避免测试里重复写常量。 */
    List<String> presetIds() {
        return AiPresets.ALL.stream().map(AiPresets.AiPreset::id).toList();
    }
}
