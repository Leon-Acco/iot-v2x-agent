package com.dst.v2xagent.agentscope;

import com.dst.v2xagent.agui.AgUiEvent;
import com.dst.v2xagent.common.PermissionContext;
import com.dst.v2xagent.runtime.RunOrchestrator;
import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.message.Msg;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * 车联网 HarnessAgent：显式状态机主链路的 AgentScope 承载体。
 * 模型/工具/Session/Trace 由 AgentScope 抽象接入；业务管线在 RunOrchestrator（设计文档 §5.1）。
 */
@Slf4j
public class V2xHarnessAgent implements Agent {

    private final String profileId;
    private final RunOrchestrator orchestrator;
    private final StringRedisTemplate redisTemplate;
    private final Semaphore runPermits;
    private final ExecutorService runExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /** 本 Agent 正在执行的 runId 集合（interrupt 组织取消） */
    private final Map<String, Boolean> activeRuns = new ConcurrentHashMap<>();

    public V2xHarnessAgent(String profileId, RunOrchestrator orchestrator,
                           StringRedisTemplate redisTemplate, Semaphore runPermits) {
        this.profileId = profileId;
        this.orchestrator = orchestrator;
        this.redisTemplate = redisTemplate;
        this.runPermits = runPermits;
    }

    @Override
    public String getAgentId() {
        return profileId;
    }

    @Override
    public String getName() {
        return profileId;
    }

    @Override
    public void interrupt() {
        activeRuns.keySet().forEach(orchestrator::cancel);
    }

    @Override
    public void interrupt(Msg msg) {
        interrupt();
    }

    /** AG-UI 一次运行：翻译输入 → 驱动状态机 → 事件 Flux */
    public Flux<AguiEvent> runPipeline(RunAgentInput input, RuntimeContext context) {
        PermissionContext perm = context.get(PermissionContext.class);
        String threadId = input.getThreadId() != null && !input.getThreadId().isBlank()
                ? input.getThreadId() : UUID.randomUUID().toString().replace("-", "");
        String runId = input.getRunId() != null && !input.getRunId().isBlank()
                ? input.getRunId() : UUID.randomUUID().toString().replace("-", "");
        String question = latestUserText(input);

        AguiFluxSink sink = new AguiFluxSink(threadId, runId, redisTemplate);
        if (perm == null) {
            sink.emit(AgUiEvent.runError("UNAUTHORIZED", "accept", false, "未认证或会话已过期"));
            sink.close();
            return sink.flux();
        }
        if (!runPermits.tryAcquire()) {
            sink.emit(AgUiEvent.runError("TOO_MANY_REQUESTS", "accept", true, "系统繁忙，请稍后重试"));
            sink.close();
            return sink.flux();
        }

        RunOrchestrator.RunInput runInput = new RunOrchestrator.RunInput(
                threadId, runId, profileId, question,
                input.getForwardedProps() != null ? input.getForwardedProps() : Map.of());
        activeRuns.put(runId, Boolean.TRUE);
        runExecutor.submit(() -> {
            try {
                orchestrator.run(runInput, perm, sink);
            } finally {
                activeRuns.remove(runId);
                runPermits.release();
            }
        });
        return sink.flux();
    }

    /** 取最后一条用户消息文本作为问题 */
    private String latestUserText(RunAgentInput input) {
        if (input.getMessages() == null) {
            return "";
        }
        for (int i = input.getMessages().size() - 1; i >= 0; i--) {
            AguiMessage m = input.getMessages().get(i);
            if ("user".equals(m.getRole()) && m.getTextContent() != null) {
                return m.getTextContent();
            }
        }
        return "";
    }

    // ---- CallableAgent / StreamableAgent / ObservableAgent 最小实现（主入口是 runPipeline） ----

    @Override
    public Mono<Msg> call(List<Msg> messages) {
        return Mono.empty();
    }

    @Override
    public Mono<Msg> call(List<Msg> messages, Class<?> structuredOutput) {
        return Mono.empty();
    }

    @Override
    public Mono<Msg> call(List<Msg> messages, JsonNode structuredOutput) {
        return Mono.empty();
    }

    @Override
    public Flux<Event> stream(List<Msg> messages, StreamOptions options) {
        return Flux.empty();
    }

    @Override
    public Flux<Event> stream(List<Msg> messages, StreamOptions options, Class<?> structuredOutput) {
        return Flux.empty();
    }

    @Override
    public Flux<Event> stream(List<Msg> messages, StreamOptions options, JsonNode structuredOutput) {
        return Flux.empty();
    }

    @Override
    public Mono<Void> observe(Msg msg) {
        return Mono.empty();
    }

    @Override
    public Mono<Void> observe(List<Msg> msgs) {
        return Mono.empty();
    }
}
