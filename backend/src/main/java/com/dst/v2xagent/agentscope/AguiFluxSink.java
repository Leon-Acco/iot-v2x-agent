package com.dst.v2xagent.agentscope;

import com.dst.v2xagent.agui.AgUiEvent;
import com.dst.v2xagent.runtime.RunEventSink;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agui.event.AguiEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AG-UI 事件出口：项目内部事件翻译为官方 AguiEvent 推入 Flux，含 Redis 断线缓冲。
 * 顺序红线不变：TOOL_CALL_RESULT（表格）先于 TEXT_MESSAGE_CONTENT（结论）。
 */
@Slf4j
public class AguiFluxSink implements RunEventSink {

    private final Sinks.Many<AguiEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
    private final String threadId;
    private final String runId;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong seq = new AtomicLong(0);
    private volatile boolean closed = false;

    /** 结论文本消息（Start/Content/End 状态机） */
    private String textMessageId;
    /** 最近 capability（ARGS/RESULT 归属） */
    private String lastToolCallId;

    public AguiFluxSink(String threadId, String runId, StringRedisTemplate redisTemplate) {
        this.threadId = threadId;
        this.runId = runId;
        this.redisTemplate = redisTemplate;
    }

    /** 事件流（供 adapter 返回给官方 controller） */
    public Flux<AguiEvent> flux() {
        return sink.asFlux();
    }

    @Override
    public synchronized void emit(AgUiEvent event) {
        if (closed) {
            return;
        }
        for (AguiEvent e : translate(event)) {
            sink.tryEmitNext(e);
            buffer(e);
        }
    }

    @Override
    public void emitText(String delta) {
        emit(AgUiEvent.of("TEXT_MESSAGE_CONTENT", Map.of("delta", delta)));
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        // 结论未收尾则补 TextMessageEnd，保证消息状态完整
        if (textMessageId != null) {
            sink.tryEmitNext(new AguiEvent.TextMessageEnd(threadId, runId, textMessageId));
            textMessageId = null;
        }
        sink.tryEmitComplete();
    }

    /** 翻译项目事件为官方事件（一个项目事件可能展开为多个官方事件） */
    private List<AguiEvent> translate(AgUiEvent event) {
        Map<String, Object> p = event.payload();
        switch (event.type()) {
            case "RUN_STARTED":
                return List.of(new AguiEvent.RunStarted(threadId, runId, null, null));
            case "STEP_STARTED":
                return List.of(new AguiEvent.StepStarted(threadId, runId, str(p, "stage")));
            case "STEP_FINISHED": {
                List<AguiEvent> out = new ArrayList<>();
                if ("conclude".equals(str(p, "stage"))) {
                    closeTextMessage(out);
                }
                out.add(new AguiEvent.StepFinished(threadId, runId, str(p, "stage")));
                return out;
            }
            case "TOOL_CALL_START":
                lastToolCallId = str(p, "capabilityId");
                return List.of(new AguiEvent.ToolCallStart(threadId, runId, lastToolCallId, str(p, "displayName")));
            case "TOOL_CALL_ARGS":
                return List.of(new AguiEvent.ToolCallArgs(threadId, runId, lastToolCallId, json(p.get("args"))));
            case "TOOL_CALL_RESULT":
                return List.of(new AguiEvent.ToolCallResult(threadId, runId, lastToolCallId, json(p), "tool", null));
            case "TEXT_MESSAGE_CONTENT": {
                List<AguiEvent> out = new ArrayList<>();
                if (textMessageId == null) {
                    textMessageId = UUID.randomUUID().toString().replace("-", "");
                    out.add(new AguiEvent.TextMessageStart(threadId, runId, textMessageId, "assistant"));
                }
                out.add(new AguiEvent.TextMessageContent(threadId, runId, textMessageId, str(p, "delta")));
                return out;
            }
            case "RUN_FINISHED": {
                List<AguiEvent> out = new ArrayList<>();
                closeTextMessage(out);
                // 运行元信息（followUps 等）作为 result 携带，前端可读
                out.add(new AguiEvent.RunFinished(threadId, runId, p, new AguiEvent.RunFinishedSuccessOutcome()));
                return out;
            }
            case "RUN_ERROR":
                return List.of(new AguiEvent.RunError(threadId, runId, str(p, "message"), str(p, "errorCode")));
            default:
                // 域事件（CHART_SPEC / CLARIFY / REFUSE）走 CUSTOM 承载
                return List.of(new AguiEvent.Custom(threadId, runId, event.type(), p));
        }
    }

    private void closeTextMessage(List<AguiEvent> out) {
        if (textMessageId != null) {
            out.add(new AguiEvent.TextMessageEnd(threadId, runId, textMessageId));
            textMessageId = null;
        }
    }

    private String str(Map<String, Object> p, String key) {
        Object v = p.get(key);
        return v == null ? "" : v.toString();
    }

    private String json(Object v) {
        try {
            return mapper.writeValueAsString(v);
        } catch (Exception e) {
            return String.valueOf(v);
        }
    }

    /** Redis 事件缓冲（TTL 10 分钟，供 /ag-ui/run/{runId}/replay 断线重放） */
    private void buffer(AguiEvent e) {
        if (redisTemplate == null) {
            return;
        }
        try {
            long id = seq.incrementAndGet();
            String jsonStr = mapper.writeValueAsString(Map.of(
                    "id", id,
                    "type", e.getType().name(),
                    "payload", e));
            String key = "agui:run:" + runId + ":events";
            redisTemplate.opsForList().rightPush(key, jsonStr);
            redisTemplate.expire(key, Duration.ofMinutes(10));
        } catch (Exception ex) {
            log.warn("事件缓冲写入失败（不影响本次推送）: {}", ex.getMessage());
        }
    }
}
