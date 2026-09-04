package com.dst.v2xagent.copilot;

import com.dst.v2xagent.agentscope.AgentScopeModels;
import com.dst.v2xagent.agentscope.AguiFluxSink;
import com.dst.v2xagent.agui.AgUiEvent;
import com.dst.v2xagent.common.PermissionContext;
import com.dst.v2xagent.runtime.RunOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.Model;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * 车联网 Copilot 总控 Agent（AgentScope 多 Agent 编排入口）。
 * Supervisor 路由 -> 领域专家 ReActAgent；异常探查走确定性 Workflow。
 * 数据源只读模拟库 dst_v2x_sim；AG-UI 事件契约与旧链路一致。
 */
@Slf4j
public class V2xCopilotAgent implements Agent {

    private final String profileId;
    private final CopilotRuntime runtime;
    private final CopilotQueryRouter queries;
    private final SupervisorRouter router;
    private final StringRedisTemplate redisTemplate;
    private final Semaphore runPermits;
    private final ExecutorService runExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ObjectMapper mapper = new ObjectMapper();

    /** 运行中的 runId（interrupt 组织取消） */
    private final Map<String, Boolean> activeRuns = new ConcurrentHashMap<>();
    private final Map<String, Boolean> cancelFlags = new ConcurrentHashMap<>();

    public V2xCopilotAgent(String profileId, CopilotRuntime runtime, CopilotQueryRouter queries,
                           StringRedisTemplate redisTemplate, Semaphore runPermits) {
        this.profileId = profileId;
        this.runtime = runtime;
        this.queries = queries;
        this.router = new SupervisorRouter(runtime.structured());
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
        activeRuns.keySet().forEach(id -> cancelFlags.put(id, Boolean.TRUE));
    }

    @Override
    public void interrupt(Msg msg) {
        interrupt();
    }

    /** AG-UI 一次运行：翻译输入 -> 虚拟线程驱动多 Agent 编排 -> 事件 Flux */
    public Flux<AguiEvent> runPipeline(RunAgentInput input, RuntimeContext context) {
        PermissionContext perm = context.get(PermissionContext.class);
        String threadId = input.getThreadId() == null || input.getThreadId().isBlank()
                ? UUID.randomUUID().toString().replace("-", "") : input.getThreadId();
        String runId = input.getRunId() == null || input.getRunId().isBlank()
                ? UUID.randomUUID().toString().replace("-", "") : input.getRunId();
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

        List<Msg> history = historyMessages(input);
        RunOrchestrator.RunInput runInput = new RunOrchestrator.RunInput(
                threadId, runId, profileId, question,
                input.getForwardedProps() != null ? input.getForwardedProps() : Map.of());
        activeRuns.put(runId, Boolean.TRUE);
        runExecutor.submit(() -> {
            try {
                run(runInput, history, perm, sink);
            } finally {
                activeRuns.remove(runId);
                runPermits.release();
            }
        });
        return sink.flux();
    }

    /** 主编排流程：闲聊门栋 -> Supervisor 路由 -> 专家 / 异常 Workflow */
    private void run(RunOrchestrator.RunInput input, List<Msg> history,
                     PermissionContext ctx, AguiFluxSink sink) {
        long start = System.currentTimeMillis();
        String traceId = UUID.randomUUID().toString().replace("-", "");
        // 追踪上下文：本线程驱动整条管线，ThreadLocal 在此生效
        com.dst.v2xagent.observability.trace.TraceContext.begin(traceId);
        String status = "SUCCESS";
        String errorCode = null;
        String failedStage = null;
        String routeTag = null;
        Integer rowCount = null;
        StringBuilder conclusion = new StringBuilder();
        String sessionId = runtime.sessions().resolveSessionId(
                ctx.tenantId(), input.threadId(), ctx.userId(), profileId);
        try {
            sink.emit(AgUiEvent.runStarted(input.runId(), input.threadId(), traceId));

            // 强制路由：用户通过界面指定工具（forwardedProps 仅影响路由选择，不参与鉴权）
            String forcedTool = forcedToolOf(input.forwardedProps());
            if (forcedTool != null) {
                if (!CopilotToolCatalog.exists(forcedTool)) {
                    sink.emitText("不支持的工具: " + forcedTool);
                    finishOk(sink, input, traceId, defaultFollowUps());
                    return;
                }
                routeTag = "forced";
                sink.emit(AgUiEvent.stepStarted("route", "已指定工具，直接执行"));
                sink.emit(AgUiEvent.stepFinished("route"));
                sink.emit(AgUiEvent.of("AGENT_ROUTE", Map.of(
                        "route", "forced", "reason", "用户指定工具",
                        "vehicle", "", "forcedTool", forcedTool)));
                SpecialistAgents.Domain forcedDomain = CopilotToolCatalog.domainOf(forcedTool);
                try (com.dst.v2xagent.observability.trace.TraceContext.Span ignored =
                             com.dst.v2xagent.observability.trace.TraceContext.span("agent", "forced:" + forcedTool)) {
                    specialistRun(forcedDomain, history, ctx, sink, conclusion, input.runId(), forcedTool);
                }
                persistTurn(ctx, sessionId, input, routeTag, rowCount, conclusion, forcedTool, sink);
                finishOk(sink, input, traceId, followUpsFor(routeTag));
                return;
            }

            String smallTalk = smallTalkReply(input.question());
            if (smallTalk != null) {
                sink.emitText(smallTalk);
                finishOk(sink, input, traceId, defaultFollowUps());
                return;
            }

            sink.emit(AgUiEvent.stepStarted("route", "正在分配专家 Agent"));
            checkCancelled(input.runId());
            SupervisorRouter.RouteResult rr;
            try (com.dst.v2xagent.observability.trace.TraceContext.Span ignored =
                         com.dst.v2xagent.observability.trace.TraceContext.span("agent", "route")) {
                rr = router.route(input.question(), historyTail(history));
            }
            routeTag = rr.route();
            sink.emit(AgUiEvent.stepFinished("route"));
            sink.emit(AgUiEvent.of("AGENT_ROUTE", Map.of(
                    "route", rr.route(), "reason", rr.reason() == null ? "" : rr.reason(),
                    "vehicle", rr.vehicle() == null ? "" : rr.vehicle())));

            if ("meta".equals(rr.route())) {
                sink.emitText(metaReply(ctx));
                finishOk(sink, input, traceId, defaultFollowUps());
                return;
            }
            if ("chat".equals(rr.route())) {
                sink.emitText(GUIDE_REPLY);
                finishOk(sink, input, traceId, defaultFollowUps());
                return;
            }
            if ("anomaly".equals(rr.route())) {
                try (com.dst.v2xagent.observability.trace.TraceContext.Span ignored =
                             com.dst.v2xagent.observability.trace.TraceContext.span("agent", "anomaly_workflow")) {
                    rowCount = anomalyWorkflow(input, rr, ctx, sink, conclusion);
                }
            } else {
                SpecialistAgents.Domain domain = switch (rr.route()) {
                    case "vehicle" -> SpecialistAgents.Domain.VEHICLE;
                    case "alarm" -> SpecialistAgents.Domain.ALARM;
                    case "fault" -> SpecialistAgents.Domain.FAULT;
                    case "mileage" -> SpecialistAgents.Domain.MILEAGE;
                    default -> SpecialistAgents.Domain.CROSS;
                };
                try (com.dst.v2xagent.observability.trace.TraceContext.Span ignored =
                             com.dst.v2xagent.observability.trace.TraceContext.span("agent", "specialist:" + domain)) {
                    specialistRun(domain, history, ctx, sink, conclusion, input.runId(), null);
                }
            }

            persistTurn(ctx, sessionId, input, routeTag, rowCount, conclusion, null, sink);
            finishOk(sink, input, traceId, followUpsFor(routeTag));
        } catch (CancelledException e) {
            status = "CANCELLED";
            log.info("copilot run {} cancelled", input.runId());
        } catch (Exception e) {
            status = "FAILED";
            errorCode = "INTERNAL_ERROR";
            failedStage = "copilot";
            log.error("copilot run {} failed", input.runId(), e);
            sink.emit(AgUiEvent.runError("INTERNAL_ERROR", "copilot", true,
                    "系统内部错误，请稍后重试"));
        } finally {
            // 追踪落库（失败只告警）并清理上下文
            try {
                runtime.traceRecorder().flush(traceId, com.dst.v2xagent.observability.trace.TraceContext.spans());
            } catch (Exception ignore) {
                // ignore
            }
            com.dst.v2xagent.observability.trace.TraceContext.end();
            cancelFlags.remove(input.runId());
            runtime.audit().record(ctx, input, traceId,
                    routeTag == null ? null : "copilot:" + routeTag,
                    null, status, errorCode, failedStage,
                    System.currentTimeMillis() - start, rowCount);
            sink.close();
        }
    }

    /** 专家路径：构建领域 ReActAgent，驱动事件流并翻译为 AG-UI 事件；forcedTool 非空时注入指定工具指令 */
    private void specialistRun(SpecialistAgents.Domain domain, List<Msg> history,
                               PermissionContext ctx, AguiFluxSink sink,
                               StringBuilder conclusion, String runId, String forcedTool) {
        ToolResultBridge bridge = new ToolResultBridge(sink, runtime.charts());
        CopilotTools tools = new CopilotTools(queries, ctx, bridge);
        VisualizationTools vizTools = new VisualizationTools(bridge);
        Model model = AgentScopeModels.concludeModel(runtime.llm().activeProvider());
        sink.emit(AgUiEvent.stepStarted("invoke", "专家 Agent 正在分析"));
        checkCancelled(runId);
        try (ReActAgent agent = SpecialistAgents.build(domain, tools, vizTools, model, forcedTool)) {
            agent.streamEvents(history)
                    .doOnNext(ev -> translateAgentEvent(ev, sink, conclusion))
                    .blockLast(Duration.ofSeconds(180));
        }
        checkCancelled(runId);
        sink.emit(AgUiEvent.stepFinished("invoke"));
    }

    /** AgentScope 事件 -> 前端事件：文本增量直接流式转发（表格/图表由工具桥推送），思考块转 THINKING 帧 */
    private void translateAgentEvent(io.agentscope.core.event.AgentEvent ev, AguiFluxSink sink,
                                     StringBuilder conclusion) {
        if (ev instanceof TextBlockDeltaEvent t) {
            conclusion.append(t.getDelta());
            sink.emitText(t.getDelta());
        } else if (ev instanceof io.agentscope.core.event.ThinkingBlockStartEvent) {
            log.debug("copilot thinking block started");
            sink.emit(AgUiEvent.of("THINKING_START", Map.of()));
        } else if (ev instanceof io.agentscope.core.event.ThinkingBlockDeltaEvent d) {
            sink.emit(AgUiEvent.of("THINKING_DELTA",
                    Map.of("delta", d.getDelta() == null ? "" : d.getDelta())));
        } else if (ev instanceof io.agentscope.core.event.ThinkingBlockEndEvent) {
            sink.emit(AgUiEvent.of("THINKING_END", Map.of()));
        } else if (ev instanceof ExceedMaxItersEvent) {
            String note = "\n\n（分析过程较长，已基于目前数据给出结论）";
            conclusion.append(note);
            sink.emitText(note);
        }
    }

    /** 异常探查 Workflow：确定性三路取证（告警 + 故障 + 里程）-> 报告流式结论 */
    private Integer anomalyWorkflow(RunOrchestrator.RunInput input, SupervisorRouter.RouteResult rr,
                                    PermissionContext ctx, AguiFluxSink sink, StringBuilder conclusion) {
        if (rr.vehicle() == null || rr.vehicle().isBlank()) {
            sink.emitText("请告诉我要分析哪台车（车牌号或 VIN），例如：粤BD96880 最近怎么回事");
            return null;
        }
        List<Map<String, Object>> candidates = queries.resolveVehicles(rr.vehicle(), ctx);
        if (candidates.isEmpty()) {
            sink.emitText("没有找到车辆 " + rr.vehicle() + "，请检查车牌号或 VIN 是否正确。");
            return null;
        }
        if (candidates.size() > 1) {
            StringBuilder sb = new StringBuilder("找到多台匹配车辆，请确认要分析哪一台：\n");
            for (Map<String, Object> c : candidates) {
                sb.append("- ").append(c.get("plate_no")).append("（")
                        .append(c.get("fleet_name")).append("）\n");
            }
            sink.emitText(sb.toString());
            return null;
        }
        String vin = String.valueOf(candidates.get(0).get("vin"));
        String plate = String.valueOf(candidates.get(0).get("plate_no"));
        TimeRanges.Range range = TimeRanges.parse(rr.timeRange(), queries.baseDate());

        sink.emit(AgUiEvent.stepStarted("invoke", "正在多源取证"));
        checkCancelled(input.runId());
        ToolResultBridge bridge = new ToolResultBridge(sink, runtime.charts());
        Map<String, Object> args = Map.of("vehicle", plate, "time_range_display", range.display());
        SimQueries.Outcome alarmOc = queries.alarmsByType(range, vin, ctx);
        bridge.emitOutcome(alarmOc, args);
        SimQueries.Outcome faultOc = queries.faultList(range, vin, false, ctx);
        bridge.emitOutcome(faultOc, args);
        SimQueries.Outcome mileOc = queries.mileageDaily(range, vin, ctx);
        bridge.emitOutcome(mileOc, args);
        sink.emit(AgUiEvent.stepFinished("invoke"));

        sink.emit(AgUiEvent.stepStarted("conclude", "正在生成异常分析报告"));
        checkCancelled(input.runId());
        String user = "用户问题：" + input.question() + "\n"
                + "车辆：" + plate + "（VIN " + vin + "）\n"
                + "时间范围：" + range.display() + "\n\n"
                + "【告警类型统计】\n" + compactJson(alarmOc) + "\n\n"
                + "【故障明细】\n" + compactJson(faultOc) + "\n\n"
                + "【每日里程趋势】\n" + compactJson(mileOc);
        runtime.streaming().stream(
                new com.dst.v2xagent.runtime.spi.StreamingModelClient.StreamingRequest(
                        REPORT_PROMPT, user, 0.3, 45000),
                delta -> {
                    conclusion.append(delta);
                    sink.emitText(delta);
                });
        sink.emit(AgUiEvent.stepFinished("conclude"));
        return alarmOc.table().rowCount() + faultOc.table().rowCount() + mileOc.table().rowCount();
    }

    // ---------- helpers ----------

    /** 平台元信息问答（确定性内容，不调模型） */
    private String metaReply(PermissionContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("我是由 Supervisor + 多专家 Agent 协作的车联网智能助手。\n\n");
        sb.append("**数据源**：").append(queries.sourceName())
                .append("，数据基准日 ").append(queries.baseDate())
                .append("。\n");
        sb.append("**权限**：").append(ctx.isAdmin() ? "管理员，可见全部车队" : "可见车队：" + ctx.fleetIds())
                .append("。\n\n");
        sb.append("**专家分工**：\n");
        sb.append("- 车辆专家：档案 / 离线清单 / 最后位置\n");
        sb.append("- 告警专家：类型统计 / 明细 / 车队对比\n");
        sb.append("- 故障专家：部位统计 / 明细\n");
        sb.append("- 里程充电专家：车队里程对比 / 每日里程 / 充电统计\n");
        sb.append("- 异常分析 Workflow：某台车的多源取证与报告\n\n");
        sb.append("每次查询会自动展示表格和图表，你可以直接问。");
        return sb.toString();
    }

    private static final String REPORT_PROMPT =
            "你是车联网平台的异常分析报告 Agent。基于告警统计、故障明细、里程趋势三路取证数据，解释该车的异常原因。\n"
            + "要求：\n"
            + "1. 结论先行：一句话判断主要异常原因；\n"
            + "2. 证据分点罗出：引用具体数字（次数、日期、趋势）；\n"
            + "3. 给出处置建议（是否需要检修、是否建议停运）；\n"
            + "4. 数据不足时明确说明，不编造；\n"
            + "5. 表格与图表已展示给用户，正文不重复罗列数据。";

    private static final String GUIDE_REPLY =
            "你好！我是车联网智能助手，由多个专家 Agent 协作为你服务。可以问我车辆状态、告警统计、故障明细、里程充电等问题，也可以让我分析某台车的异常原因。";

    private static final java.util.Set<String> CHITCHAT = java.util.Set.of(
            "你好", "您好", "hi", "hello", "hey",
            "在吗", "在么", "嗨", "喂", "在",
            "谢谢", "感谢", "拜拜", "再见",
            "早上好", "上午好", "下午好", "晚上好", "晚安",
            "测试", "test",
            "你是谁", "你能干什么", "你能做什么",
            "你会什么", "帮助", "help");

    private String smallTalkReply(String question) {
        if (question == null) {
            return GUIDE_REPLY;
        }
        String t = question.trim().replaceAll("[ 　!！?？。.,，~～…]+", "").toLowerCase();
        if (t.isEmpty()) {
            return GUIDE_REPLY;
        }
        return CHITCHAT.contains(t) ? GUIDE_REPLY : null;
    }

    /** 取最后一条用户消息文本 */
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

    /** 历史消息转 AgentScope 消息列表（截取最近 12 条） */
    private List<Msg> historyMessages(RunAgentInput input) {
        List<Msg> out = new ArrayList<>();
        if (input.getMessages() == null) {
            return out;
        }
        List<AguiMessage> all = input.getMessages();
        int from = Math.max(0, all.size() - 12);
        for (int i = from; i < all.size(); i++) {
            AguiMessage m = all.get(i);
            String text = m.getTextContent();
            if (text == null || text.isBlank()) {
                continue;
            }
            if ("user".equals(m.getRole())) {
                out.add(Msg.builder().role(MsgRole.USER).textContent(text).build());
            } else if ("assistant".equals(m.getRole())) {
                out.add(Msg.builder().role(MsgRole.ASSISTANT).textContent(text).build());
            }
        }
        return out;
    }

    /** 路由器上下文：最近 4 条对话文本 */
    private String historyTail(List<Msg> history) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (int i = history.size() - 1; i >= 0 && count < 4; i--, count++) {
            Msg m = history.get(i);
            io.agentscope.core.message.TextBlock tb = m.getFirstContentBlock(io.agentscope.core.message.TextBlock.class);
            if (tb != null) {
                sb.insert(0, m.getRole() + ": " + tb.getText() + "\n");
            }
        }
        return sb.toString();
    }

    /** 结果表紧凑 JSON（给报告模型，最多 15 行） */
    private String compactJson(SimQueries.Outcome oc) {
        try {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("title", oc.def().getDisplay());
            m.put("rowCount", oc.table().rowCount());
            List<String> cols = oc.def().getReturns().getColumns().stream()
                    .map(c -> c.getDisplay() + (c.getUnit() == null ? "" : "(" + c.getUnit() + ")"))
                    .toList();
            m.put("columns", cols);
            List<List<Object>> rows = new ArrayList<>();
            int limit = Math.min(15, oc.table().rowCount());
            for (int i = 0; i < limit; i++) {
                List<Object> row = new ArrayList<>();
                for (Object cell : oc.table().rows().get(i)) {
                    row.add(cell == null ? null : cell.toString());
                }
                rows.add(row);
            }
            m.put("rows", rows);
            return mapper.writeValueAsString(m);
        } catch (Exception e) {
            return "{}";
        }
    }

    private void persistTurn(PermissionContext ctx, String sessionId, RunOrchestrator.RunInput input,
                             String routeTag, Integer rowCount, StringBuilder conclusion,
                             String forcedTool, AguiFluxSink sink) {
        try {
            // 完整帧快照（tools/result/chart/visualizations/followUps/traceId）+ answer/forcedTool
            Map<String, Object> payload = sink.snapshot();
            payload.put("answer", conclusion.toString());
            payload.put("forcedTool", forcedTool);
            runtime.sessions().appendTurn(ctx.tenantId(), sessionId, input.question(),
                    routeTag == null ? null : "copilot:" + routeTag,
                    null, rowCount, conclusion.toString(),
                    conclusion.length() > 200 ? conclusion.substring(0, 200) : conclusion.toString(),
                    payload);
            // 首轮自动命名会话标题
            runtime.sessions().touchTitleIfBlank(ctx.tenantId(), sessionId, input.question());
            // 记忆提取：任务摘要 / 用户偏好 / 异常模式沉淀（Memory 是线索不是事实）
            runtime.memoryExtractor().extractAndRemember(ctx.tenantId(), ctx.userId(), input.question(),
                    conclusion.toString(), routeTag == null ? "TASK" : "copilot:" + routeTag, java.util.List.of());
        } catch (Exception e) {
            log.warn("session persist failed (ignored): {}", e.getMessage());
        }
    }

    /** forwardedProps 中读取用户指定工具（仅影响路由选择，不参与鉴权） */
    private String forcedToolOf(Map<String, Object> forwardedProps) {
        if (forwardedProps == null) {
            return null;
        }
        Object v = forwardedProps.get("forcedTool");
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v);
    }

    private void finishOk(AguiFluxSink sink, RunOrchestrator.RunInput input, String traceId,
                          List<String> followUps) {
        // Agent Trace 帧：执行步骤可视化（前端步骤流渲染）
        java.util.List<java.util.Map<String, Object>> steps = new java.util.ArrayList<>();
        for (com.dst.v2xagent.observability.trace.TraceSpan s
                : com.dst.v2xagent.observability.trace.TraceContext.spans()) {
            java.util.Map<String, Object> step = new java.util.LinkedHashMap<>();
            step.put("type", s.spanType());
            step.put("name", s.name() == null ? "" : s.name());
            step.put("duration_ms", s.durationMs());
            step.put("status", s.status());
            steps.add(step);
        }
        sink.emit(AgUiEvent.of("AGENT_TRACE", Map.of(
                "trace_id", traceId, "steps", steps)));
        sink.emit(AgUiEvent.of("RUN_FINISHED", Map.of(
                "runId", input.runId(), "traceId", traceId, "followUps", followUps)));
    }

    private List<String> defaultFollowUps() {
        return List.of("近 7 天各类告警次数",
                "粤BD96880 最近怎么回事",
                "离线超 24 小时的车有哪些");
    }

    private List<String> followUpsFor(String routeTag) {
        if (routeTag == null) {
            return defaultFollowUps();
        }
        return switch (routeTag) {
            case "alarm" -> List.of("看看告警明细", "车队告警对比一下", "换成近 30 天再看看");
            case "fault" -> List.of("只看未恢复的故障", "按部位统计一下");
            case "mileage" -> List.of("各车队里程对比", "看看充电统计");
            case "vehicle" -> List.of("它的最后位置在哪", "离线超 24 小时的车有哪些");
            case "anomaly" -> List.of("看看它的告警明细", "换成近 30 天再分析一次");
            case "forced" -> List.of("换个时间范围再查一次", "切回智能路由继续追问");
            default -> defaultFollowUps();
        };
    }

    private void checkCancelled(String runId) {
        if (cancelFlags.containsKey(runId)) {
            throw new CancelledException();
        }
    }

    private static class CancelledException extends RuntimeException {}

    // ---- CallableAgent / StreamableAgent / ObservableAgent minimal impls (main entry is runPipeline) ----

    @Override
    public Mono<Msg> call(List<Msg> messages) {
        return Mono.empty();
    }

    @Override
    public Mono<Msg> call(List<Msg> messages, Class<?> structuredOutput) {
        return Mono.empty();
    }

    @Override
    public Mono<Msg> call(List<Msg> messages, com.fasterxml.jackson.databind.JsonNode structuredOutput) {
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
    public Flux<Event> stream(List<Msg> messages, StreamOptions options,
                              com.fasterxml.jackson.databind.JsonNode structuredOutput) {
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
