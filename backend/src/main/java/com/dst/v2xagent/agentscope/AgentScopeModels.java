package com.dst.v2xagent.agentscope;

import com.dst.v2xagent.llm.LlmProperties;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.formatter.OpenAIChatFormatter;

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

    /** 结论模型：流式 */
    public static OpenAIChatModel concludeModel(LlmProperties.Provider provider) {
        return OpenAIChatModel.builder()
                .apiKey(provider.getApiKey())
                .baseUrl(provider.getBaseUrl())
                .modelName(provider.getConcludeModel())
                .stream(true)
                .formatter(new OpenAIChatFormatter())
                .build();
    }
}
