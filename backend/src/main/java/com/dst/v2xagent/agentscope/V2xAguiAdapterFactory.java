package com.dst.v2xagent.agentscope;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.agui.adapter.AguiAgentAdapterFactory;

/** AG-UI 适配器工厂：为每个 Agent 创建项目定制适配器。 */
public class V2xAguiAdapterFactory implements AguiAgentAdapterFactory {

    @Override
    public AguiAgentAdapter create(Agent agent, AguiAdapterConfig config) {
        return new V2xAguiAgentAdapter(agent, config);
    }
}
