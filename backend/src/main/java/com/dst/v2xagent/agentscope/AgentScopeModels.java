package com.dst.v2xagent.agentscope;

import com.dst.v2xagent.llm.LlmProperties;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;

import java.util.Map;

/**
 * AgentScope 模型工厂：按当前供应商（deepseek/glm/kimi）构建模型实例。
 * 框架类型只允许出现在本 adapter 包（设计文档 §18）。
 */
public final class AgentScopeModels {

    private AgentScopeModels() {}

    /** 抽取模型：非流式、低温度 */
    public static OpenAIChatModel extractModel(LlmProperties.Provider provider) {
        return OpenAIChatModel.builder()
                .apiKey(provider.getApiKey())
                .baseUrl(provider.getBaseUrl())
                .modelName(provider.getExtractModel())
                .stream(false)
                .formatter(new OpenAIChatFormatter())
                .build();
    }

    /**
     * 结论模型：流式；开启 thinking 时透传 GLM 思考参数（请求体顶层 thinking:{type:enabled}），
     * 模型返回 reasoning_content 增量 → ThinkingBlock → THINKING 帧。
     * 注意：GLM 思考模式与 temperature 互斥，此处不设置 temperature。
     */
    public static OpenAIChatModel concludeModel(LlmProperties.Provider provider) {
        OpenAIChatModel.Builder builder = OpenAIChatModel.builder()
                .apiKey(provider.getApiKey())
                .baseUrl(provider.getBaseUrl())
                .modelName(provider.getConcludeModel())
                .stream(true)
                .formatter(new OpenAIChatFormatter());
        if (Boolean.TRUE.equals(provider.getThinkingEnabled())) {
            builder.generateOptions(GenerateOptions.builder()
                    .stream(true)
                    .additionalBodyParams(Map.of("thinking", Map.of("type", "enabled")))
                    .build());
        }
        return builder.build();
    }
}
