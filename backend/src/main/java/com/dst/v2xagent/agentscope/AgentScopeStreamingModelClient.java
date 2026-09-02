package com.dst.v2xagent.agentscope;

import com.dst.v2xagent.common.ApiException;
import com.dst.v2xagent.runtime.spi.StreamingModelClient;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import java.time.Duration;
import java.util.List;

/** 流式模型调用的 AgentScope 适配器（结论节点：token 逐帧回吐）。 */
public class AgentScopeStreamingModelClient implements StreamingModelClient {

    private final OpenAIChatModel model;

    public AgentScopeStreamingModelClient(OpenAIChatModel model) {
        this.model = model;
    }

    @Override
    public void stream(StreamingRequest request, TokenSink sink) {
        GenerateOptions options = GenerateOptions.builder()
                .stream(true)
                .temperature(request.temperature())
                .build();
        List<Msg> messages = List.of(
                Msg.builder().role(MsgRole.SYSTEM).textContent(request.systemPrompt()).build(),
                Msg.builder().role(MsgRole.USER).textContent(request.userPrompt()).build());
        try {
            model.stream(messages, List.of(), options)
                    .timeout(Duration.ofMillis(request.timeoutMs()))
                    .doOnNext(resp -> {
                        for (var block : resp.getContent()) {
                            if (block instanceof TextBlock t
                                    && t.getText() != null && !t.getText().isEmpty()) {
                                sink.onToken(t.getText());
                            }
                        }
                    })
                    .blockLast();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw AgentScopeErrorMapper.map(e, "conclude", "结论生成超时");
        }
    }
}
