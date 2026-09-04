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
import java.util.LinkedHashMap;
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

    // ---- 帧快照收集器（会话恢复数据源：payload_json） ----
    /** 轮次级快照（chart / result / followUps / traceId） */
    private final Map<String, Object> snap = new LinkedHashMap<>();
    /** 工具调用叙事快照 */
    private final List<Map<String, Object>> snapTools = new ArrayList<>();
    /** 可视化 UI Schema 快照（上限 6 张） */
    private final List<Map<String, Object>> snapVizs = new ArrayList<>();

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
        collect(event);
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
            case "THINKING_START":
                return List.of(new AguiEvent.Custom(threadId, runId, "THINKING", Map.of("phase", "start")));
            case "THINKING_DELTA":
                return List.of(new AguiEvent.Custom(threadId, runId, "THINKING",
                        Map.of("phase", "delta", "delta", str(p, "delta"))));
            case "THINKING_END":
                return List.of(new AguiEvent.Custom(threadId, runId, "THINKING", Map.of("phase", "end")));
            default:
                // 域事件（CHART_SPEC / CLARIFY / REFUSE）走 CUSTOM 承载
                return List.of(new AguiEvent.Custom(threadId, runId, event.type(), p));
        }
    }

    /**
     * 帧快照收集：运行期间的 TOOL_CALL、CHART_SPEC、VIS_SPEC、RUN_FINISHED 帧收集为
     * 可恢复快照（落 agent_session_message.payload_json），失败不影响推送。
     */
    private void collect(AgUiEvent event) {
        try {
            Map<String, Object> p = event.payload();
            switch (event.type()) {
                case "TOOL_CALL_START" -> {
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("id", str(p, "capabilityId"));
                    t.put("name", str(p, "displayName"));
                    t.put("status", "done");
                    t.put("args", null);
                    snapTools.add(t);
                }
                case "TOOL_CALL_ARGS" -> {
                    if (!snapTools.isEmpty()) {
                        snapTools.get(snapTools.size() - 1).put("args", p.get("args"));
                    }
                }
                case "TOOL_CALL_RESULT" -> {
                    Map<String, Object> trunc = truncateTable(p);
                    if (!snapTools.isEmpty()) {
                        snapTools.get(snapTools.size() - 1).put("result", trunc);
                    }
                    snap.put("result", trunc);
                }
                case "CHART_SPEC" -> snap.put("chart", truncateChart(p));
                case "VIS_SPEC" -> {
                    if (snapVizs.size() < 6) {
                        snapVizs.add(p);
                    }
                }
                case "RUN_FINISHED" -> {
                    snap.put("followUps", p.get("followUps"));
                    snap.put("traceId", p.get("traceId"));
                }
                default -> { }
            }
        } catch (Exception ignore) {
            // 快照收集失败不影响本次推送
        }
    }

    /** 表格载荷截断：rows 存前 20 行、剔 sqlSnapshot，保证 payload_json 体积可控 */
    private Map<String, Object> truncateTable(Map<String, Object> p) {
        Map<String, Object> out = new LinkedHashMap<>(p);
        if (out.get("rows") instanceof List<?> rows && rows.size() > 20) {
            out.put("rows", new ArrayList<>(rows.subList(0, 20)));
        }
        if (out.get("stats") instanceof Map<?, ?> stats) {
            Map<String, Object> s = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : stats.entrySet()) {
                if (!"sqlSnapshot".equals(e.getKey())) {
                    s.put(String.valueOf(e.getKey()), e.getValue());
                }
            }
            out.put("stats", s);
        }
        return out;
    }

    /** 图表快照只保留 chartType/chartAlternatives/notes（前端红线：自构 option 不消费 spec） */
    private Map<String, Object> truncateChart(Map<String, Object> p) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("chartType", p.get("chartType"));
        out.put("chartAlternatives", p.get("chartAlternatives"));
        out.put("notes", p.get("notes"));
        return out;
    }

    /** 运行帧快照（落库前由调用方补充 answer/forcedTool），供会话恢复 hydrate */
    public Map<String, Object> snapshot() {
        snap.put("tools", new ArrayList<>(snapTools));
        snap.put("visualizations", new ArrayList<>(snapVizs));
        return snap;
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
