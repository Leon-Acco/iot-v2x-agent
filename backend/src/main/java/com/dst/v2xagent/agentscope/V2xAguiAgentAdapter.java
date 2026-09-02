package com.dst.v2xagent.agentscope;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.RunAgentInput;
import reactor.core.publisher.Flux;

/** AG-UI 适配器：V2xHarnessAgent 走项目状态机管线，其余类型回退官方转换。 */
public class V2xAguiAgentAdapter extends AguiAgentAdapter {

    private final Agent agent;

    public V2xAguiAgentAdapter(Agent agent, AguiAdapterConfig config) {
        super(agent, config);
        this.agent = agent;
    }

    @Override
    public Flux<AguiEvent> run(RunAgentInput input, RuntimeContext runtimeContext) {
        if (agent instanceof V2xHarnessAgent v2x) {
            return v2x.runPipeline(input, runtimeContext);
        }
        return super.run(input, runtimeContext);
    }
}
