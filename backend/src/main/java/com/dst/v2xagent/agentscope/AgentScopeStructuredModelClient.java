package com.dst.v2xagent.agentscope;

import com.dst.v2xagent.common.ApiException;
import com.dst.v2xagent.runtime.spi.StructuredModelClient;
import io.agentscope.core.formatter.ResponseFormat;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 结构化模型调用的 AgentScope 适配器（抽取节点：json_object 约束 + 低温度）。 */
public class AgentScopeStructuredModelClient implements StructuredModelClient {

    private final OpenAIChatModel model;

    public AgentScopeStructuredModelClient(OpenAIChatModel model) {
        this.model = model;
    }

    @Override
    public String generate(StructuredRequest request) {
        GenerateOptions options = GenerateOptions.builder()
                .stream(false)
                .temperature(request.temperature())
                .responseFormat(ResponseFormat.jsonObject())
                .build();
        List<Msg> messages = List.of(
                Msg.builder().role(MsgRole.SYSTEM)
                        .textContent(request.systemPrompt()
                                + " 输出必须符合此 JSON Schema：" + request.outputSchema())
                        .build(),
                Msg.builder().role(MsgRole.USER).textContent(request.userPrompt()).build());
        try {
            ChatResponse resp = model.stream(messages, List.of(), options)
                    .timeout(Duration.ofMillis(request.timeoutMs()))
                    .blockLast();
            String content = resp == null ? "" : resp.getContent().stream()
                    .filter(TextBlock.class::isInstance)
                    .map(TextBlock.class::cast)
                    .map(TextBlock::getText)
                    .collect(Collectors.joining());
            if (content.isBlank()) {
                throw ApiException.schemaInvalid("模型返回空内容");
            }
            return content;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw AgentScopeErrorMapper.map(e, "understand", "意图理解超时");
        }
    }

    /**
     * 工具选择模式（能力路由 function calling）：
     * 候选工具经 SDK ToolSchema 透传，tool_choice=auto 且禁并行，模型以 tool_calls 返回选择与参数。
     * 兜底：模型未走 tool_calls 而输出文本 JSON 时，尝试按 {tool_name/name, arguments/params} 解析。
     */
    @Override
    public ToolCall generateWithTools(StructuredRequest request) {
        if (request.tools() == null || request.tools().isEmpty()) {
            throw ApiException.schemaInvalid("工具选择模式要求非空 tools 列表");
        }
        List<ToolSchema> tools = request.tools().stream()
                .map(t -> ToolSchema.builder()
                        .name(t.name())
                        .description(t.description())
                        .parameters(t.parameters() == null ? Map.of() : t.parameters())
                        .build())
                .toList();
        GenerateOptions options = GenerateOptions.builder()
                .stream(false)
                .temperature(request.temperature())
                .toolChoice(new ToolChoice.Auto())
                .parallelToolCalls(false)
                .build();
        List<Msg> messages = List.of(
                Msg.builder().role(MsgRole.SYSTEM).textContent(request.systemPrompt()).build(),
                Msg.builder().role(MsgRole.USER).textContent(request.userPrompt()).build());
        try {
            ChatResponse resp = model.stream(messages, tools, options)
                    .timeout(Duration.ofMillis(request.timeoutMs()))
                    .blockLast();
            if (resp == null) {
                throw ApiException.schemaInvalid("模型返回空响应");
            }
            // 优先取 tool_calls
            List<ToolUseBlock> calls = resp.getContent().stream()
                    .filter(ToolUseBlock.class::isInstance)
                    .map(ToolUseBlock.class::cast)
                    .toList();
            if (!calls.isEmpty()) {
                ToolUseBlock first = calls.get(0);
                return new ToolCall(first.getName(), first.getInput() == null ? Map.of() : first.getInput());
            }
            // 文本兜底：模型以 JSON 文本表达工具调用时做格式级解析（业务校验留给调用方）
            String content = resp.getContent().stream()
                    .filter(TextBlock.class::isInstance)
                    .map(TextBlock.class::cast)
                    .map(TextBlock::getText)
                    .collect(Collectors.joining());
            ToolCall fallback = parseTextToolCall(content);
            if (fallback != null) {
                return fallback;
            }
            throw ApiException.schemaInvalid("模型未返回工具调用");
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw AgentScopeErrorMapper.map(e, "understand", "意图理解超时");
        }
    }

    /** 文本兜底解析：宽容提取 {tool_name/name, arguments/params} 结构（无业务语义） */
    @SuppressWarnings("unchecked")
    private ToolCall parseTextToolCall(String content) {
        if (content == null) {
            return null;
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> map = mapper.readValue(content.substring(start, end + 1), Map.class);
            Object name = map.get("tool_name") != null ? map.get("tool_name") : map.get("name");
            if (name == null) {
                return null;
            }
            Object args = map.get("arguments") != null ? map.get("arguments") : map.get("params");
            Map<String, Object> arguments = args instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : Map.of();
            return new ToolCall(String.valueOf(name), arguments);
        } catch (Exception e) {
            return null;
        }
    }
}
