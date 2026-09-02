package com.dst.v2xagent.runtime.spi;

/**
 * 结构化模型调用 SPI（框架隔离带：项目自有接口，不依赖任何 Agent 框架类型）
 * 用于意图/槽位抽取：输入 prompt + JSON Schema，输出受约束的 JSON 文本。
 */
public interface StructuredModelClient {

    /** 结构化请求 */
    record StructuredRequest(
            String systemPrompt,
            String userPrompt,
            /** 输出 JSON Schema 文本（强约束，模型只能产出该结构） */
            String outputSchema,
            /** 采样温度（抽取默认 0~0.1） */
            double temperature,
            int timeoutMs
    ) {}

    /**
     * 生成结构化输出。
     * @return 模型产出的 JSON 文本（调用方负责二次 Schema 校验）
     */
    String generate(StructuredRequest request);
}
