package com.dst.v2xagent.agentscope;

import com.dst.v2xagent.agui.auth.AuthService;
import com.dst.v2xagent.runtime.RunOrchestrator;
import io.agentscope.core.agui.adapter.AguiAgentAdapterFactory;
import io.agentscope.spring.boot.agui.common.AguiAgentRegistryCustomizer;
import io.agentscope.spring.boot.agui.common.AguiRuntimeContextResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.Semaphore;

/**
 * AG-UI 装配：注册两个 profile 的 Agent 工厂 + 定制适配器工厂 + 上下文解析器。
 * 两个 Agent 同一 Harness，差异只在 profile 配置（设计文档 §5.2）。
 */
@Configuration
public class V2xAguiConfig {

    @Bean
    public AguiAgentAdapterFactory v2xAguiAdapterFactory() {
        return new V2xAguiAdapterFactory();
    }

    @Bean
    public AguiRuntimeContextResolver v2xAguiRuntimeContextResolver(AuthService authService) {
        return new V2xAguiRuntimeContextResolver(authService);
    }

    @Bean
    public AguiAgentRegistryCustomizer v2xAgentRegistry(RunOrchestrator orchestrator,
                                                        StringRedisTemplate redisTemplate,
                                                        com.dst.v2xagent.copilot.CopilotQueryRouter queryRouter,
                                                        org.springframework.beans.factory.ObjectProvider<com.dst.v2xagent.copilot.CopilotRuntime> copilotProvider) {
        // 并发闸门：超限直接返回系统繁忙（所有 profile 共享）
        Semaphore runPermits = new Semaphore(200);
        return registry -> {
            registry.registerFactory("data_base",
                    () -> new V2xHarnessAgent("data_base", orchestrator, redisTemplate, runPermits));
            registry.registerFactory("device_ops",
                    () -> new V2xHarnessAgent("device_ops", orchestrator, redisTemplate, runPermits));
            // 多 Agent 编排线路（仅非 mock 模式可用：依赖模型客户端）
            com.dst.v2xagent.copilot.CopilotRuntime copilot = copilotProvider.getIfAvailable();
            if (copilot != null) {
                registry.registerFactory("fleet_copilot",
                        () -> new com.dst.v2xagent.copilot.V2xCopilotAgent(
                                "fleet_copilot", copilot, queryRouter, redisTemplate, runPermits));
            }
        };
    }
}
