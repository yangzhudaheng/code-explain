package com.codeexplain.ai;

import java.util.List;

/**
 * 内置模型预设。
 *
 * <p>只有 DeepSeek 一个：本项目的用户是中文算法学习者，DeepSeek 的价格与中文表达能力
 * 在这个场景最合适，而且它的接口是 OpenAI 兼容格式，一套代码能覆盖所有同类服务。
 *
 * <p>没有做成「六七个 Provider 随便选」：每加一个预设就要维护它的默认地址、模型名、
 * 参数差异与错误码映射，而没有真实 Key 就无法验证，最后只会变成一排点了报错的按钮。
 * 要接别家，走「自定义地址」这条路——它同样受 SSRF 校验约束。
 */
public final class AiPresets {

    private AiPresets() {
    }

    public static final String DEEPSEEK = "deepseek";

    public static final AiPreset DEFAULT =
            new AiPreset(DEEPSEEK, "DeepSeek", "https://api.deepseek.com",
                    "deepseek-chat", List.of("deepseek-chat", "deepseek-reasoner"));

    public static final List<AiPreset> ALL = List.of(DEFAULT);

    public static AiPreset byId(String id) {
        return ALL.stream().filter(p -> p.id().equals(id)).findFirst().orElse(DEFAULT);
    }

    /**
     * @param id        存库用的标识，与 {@code user_ai_settings.provider} 对应
     * @param model     默认模型；用户可以改
     * @param models    下拉候选，只是提示，最终以用户填的字符串为准
     */
    public record AiPreset(String id, String label, String baseUrl, String model, List<String> models) {
    }
}
