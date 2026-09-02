package com.dst.v2xagent.agentscope;

import com.dst.v2xagent.common.ApiException;
import com.dst.v2xagent.runtime.spi.StructuredModelClient;
import io.agentscope.core.formatter.ResponseFormat;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import java.time.Duration;
import java.util.List;
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
}
