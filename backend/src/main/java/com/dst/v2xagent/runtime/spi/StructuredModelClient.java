package com.dst.v2xagent.runtime.spi;

import java.util.List;
import java.util.Map;

/**
 * 结构化模型调用 SPI（框架隔离带：项目自有接口，不依赖任何 Agent 框架类型）
 * 两种输出模式：
 * - 纯 JSON 模式（generate）：输入 prompt + JSON Schema，输出受约束的 JSON 文本（能力工厂等复用）
 * - 工具选择模式（generateWithTools）：输入工具定义列表，模型以 tool_calls 形式返回选中的工具与参数（能力路由用）
 */
public interface StructuredModelClient {

    /** 工具定义（框架隔离的自有类型，适配层负责转 SDK 工具格式） */
    record ToolSpec(String name, String description, Map<String, Object> parameters) {}

    /** 模型返回的工具调用：工具名 + 参数（已解析为 Map） */
    record ToolCall(String name, Map<String, Object> arguments) {}

    /** 结构化请求 */
    record StructuredRequest(
            String systemPrompt,
            String userPrompt,
            /** 输出 JSON Schema 文本（纯 JSON 模式强约束；工具模式可传 null） */
            String outputSchema,
            /** 采样温度（抽取默认 0~0.1） */
            double temperature,
            int timeoutMs,
            /** 候选工具列表（空 = 纯 JSON 模式，走 generate） */
            List<ToolSpec> tools
    ) {
        /** 兼容旧调用：无工具的纯 JSON 结构化输出 */
        public StructuredRequest(String systemPrompt, String userPrompt, String outputSchema,
                                 double temperature, int timeoutMs) {
            this(systemPrompt, userPrompt, outputSchema, temperature, timeoutMs, List.of());
        }
    }

    /**
     * 生成结构化输出（纯 JSON 模式）。
     * @return 模型产出的 JSON 文本（调用方负责二次 Schema 校验）
     */
    String generate(StructuredRequest request);

    /**
     * 工具选择模式：模型从候选工具中选择一个并以 tool_calls 返回名称与参数。
     * 实现方需解析 ToolUseBlock；无 tool_calls 时可返回 null 由调用方兜底/重试。
     */
    default ToolCall generateWithTools(StructuredRequest request) {
        throw new UnsupportedOperationException("当前实现不支持工具选择模式");
    }
}
