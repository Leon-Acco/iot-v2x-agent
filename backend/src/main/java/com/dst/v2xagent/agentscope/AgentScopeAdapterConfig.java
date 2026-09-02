package com.dst.v2xagent.agentscope;

import com.dst.v2xagent.llm.LlmProperties;
import com.dst.v2xagent.runtime.spi.StreamingModelClient;
import com.dst.v2xagent.runtime.spi.StructuredModelClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AgentScope 适配器装配：仅非 mock 模式生效。
 * SPI 实现收敛到本包，业务层只依赖 runtime.spi（框架可替换）。
 */
@Configuration
@ConditionalOnExpression("'${llm.active:mock}' != 'mock'")
public class AgentScopeAdapterConfig {

    @Bean
    public StructuredModelClient structuredModelClient(LlmProperties props) {
        return new AgentScopeStructuredModelClient(
                AgentScopeModels.extractModel(props.activeProvider()));
    }

    @Bean
    public StreamingModelClient streamingModelClient(LlmProperties props) {
        return new AgentScopeStreamingModelClient(
                AgentScopeModels.concludeModel(props.activeProvider()));
    }
}
