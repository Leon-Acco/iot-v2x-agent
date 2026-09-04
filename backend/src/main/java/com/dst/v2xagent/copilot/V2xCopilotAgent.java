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


    // ==================== 思考流注入（全管线阶段决策透明化） ====================

    /** 发送一段结构化思考（分节文案直接作为 THINKING 增量） */
    private void think(AguiFluxSink sink, String text) {
        sink.emit(AgUiEvent.of("THINKING_DELTA", java.util.Map.of("delta", text)));
    }

    /** 车牌正则：省份简称汉字 + 发牌机关字母 + 序号（如 粤M32543） */
    private static final java.util.regex.Pattern PLATE_P =
            java.util.regex.Pattern.compile("[\\u4e00-\\u9fa5][A-HJ-NPR-Z][A-HJ-NPR-Z0-9]{4,6}");
    /** VIN 正则：17 位（排除易混字符） */
    private static final java.util.regex.Pattern VIN_P =
            java.util.regex.Pattern.compile("\\b[A-HJ-NPR-Z0-9]{17}\\b");
    /** 时间词正则：近 N 天/本周/上月等口语时间表达 */
    private static final java.util.regex.Pattern TIME_P = java.util.regex.Pattern.compile(
            "(?:最近|近)?\\d+\\s*(?:天|日|周|月|小时)(?:内|以[内来]?)?|今[天日]|昨[天日]|本[周月年]|上[周月年]|这[周月]");

    /** 理解阶段思考：对问题做结构化拆解（本地实体抽取——车辆/时间/意图，路由前即可给出真实内容） */
    private void thinkUnderstand(AguiFluxSink sink, String question, String historyTail) {
        String plate = findFirst(PLATE_P, question);
        String vin = plate == null ? findFirst(VIN_P, question) : null;
        String timeWord = findFirst(TIME_P, question);
        StringBuilder sb = new StringBuilder("\n【理解问题】\n");
        sb.append("用户提问：").append(question).append('\n');
        sb.append("我先把问题拆解成三个要素：\n");
        sb.append("- 目标车辆：");
        if (plate != null) {
            sb.append("识别到「").append(plate).append("」，格式为车牌号，将解析出对应 VIN 后查询");
        } else if (vin != null) {
            sb.append("识别到「").append(vin).append("」，格式为 VIN 码，可直接用于查询");
        } else {
            sb.append("问题中未指定具体车辆，按车队级统计处理");
        }
        sb.append('\n');
        sb.append("- 时间线索：");
        if (timeWord != null) {
            sb.append("「").append(timeWord).append("」，后续会解析为具体的日期区间");
        } else {
            sb.append("未提及时间，将采用默认窗口（近 7 天）");
        }
        sb.append('\n');
        sb.append("- 问题意图：").append(classifyIntent(question)).append('\n');
        if (historyTail != null && !historyTail.isBlank()) {
            sb.append("另外本会话已有历史对话，若问题里有指代（它 / 那台车 / 再看看），我会从上文继承车辆与时间上下文。\n");
        }
        sb.append("接下来交给总控路由 Agent，由它决定交给哪位领域专家处理。\n");
        think(sink, sb.toString());
    }

    /** 取正则在文本中的首个命中（无则 null） */
    private static String findFirst(java.util.regex.Pattern p, String text) {
        if (text == null) {
            return null;
        }
        java.util.regex.Matcher m = p.matcher(text);
        return m.find() ? m.group() : null;
    }

    /**
     * 关键词快路由：问题恰好命中一个领域词时直达专家（零 LLM 成本、零延迟、不受 GLM 慢/额度影响）。
     * 保守规则：0 个或 ≥2 个领域词命中一律返回 null 交给模型路由；
     * anomaly 类意图（怎么回事/为什么…）必须有车牌/VIN 才短路，否则交给模型判断。
     */
    private static String fastPathRoute(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }
        String t = question.toLowerCase();
        boolean hasVehicle = findFirst(PLATE_P, question) != null || findFirst(VIN_P, question) != null;
        if (hasVehicle && (t.contains("怎么回事") || t.contains("异常") || t.contains("为什么") || t.contains("哪里坏"))) {
            return "anomaly";
        }
        java.util.Set<String> hits = new java.util.HashSet<>();
        if (t.contains("告警") || t.contains("报警")) {
            hits.add("alarm");
        }
        if (t.contains("故障")) {
            hits.add("fault");
        }
        if (t.contains("里程") || t.contains("充电") || t.contains("行驶")) {
            hits.add("mileage");
        }
        if (t.contains("离线") || t.contains("在线") || t.contains("位置") || t.contains("在哪")) {
            hits.add("vehicle");
        }
        return hits.size() == 1 ? hits.iterator().next() : null;
    }

    /** 意图关键词分类：给理解阶段一个初步判断（最终以路由模型为准） */
    private static String classifyIntent(String q) {
        if (q == null) {
            return "未识别";
        }
        String t = q.toLowerCase();
        if (t.contains("怎么回事") || t.contains("异常") || t.contains("为什么") || t.contains("哪里坏")) {
            return "异常诊断——需要多源证据交叉解释某台车的异常表现";
        }
        if (t.contains("告警") || t.contains("报警")) {
            return "告警分析——统计或列举告警数据";
        }
        if (t.contains("故障")) {
            return "故障诊断——定位故障部位与明细";
        }
        if (t.contains("里程") || t.contains("行驶")) {
            return "里程分析——车队/车辆里程统计与趋势";
        }
        if (t.contains("充电")) {
            return "充电分析——充电行为统计";
        }
        if (t.contains("位置") || t.contains("在哪") || t.contains("离线") || t.contains("在线")) {
            return "车辆状态——在离线状态与位置信息";
        }
        if (t.contains("对比") || t.contains("比较") || t.contains("哪个")) {
            return "对比分析——需要跨车队/跨域横向比较";
        }
        return "开放问题——具体方向待路由模型判定";
    }

    /** 各领域专家的一句话职责（思考流与路由说明共用） */
    private static String domainDesc(String route) {
        return switch (route == null ? "" : route) {
            case "vehicle" -> "车辆状态专家（负责车辆档案、在离线状态、最后位置）";
            case "alarm" -> "告警分析专家（负责告警类型统计、明细、车队对比）";
            case "fault" -> "故障诊断专家（负责故障部位统计与明细）";
            case "mileage" -> "里程分析专家（负责车队里程对比、每日里程、充电统计）";
            case "anomaly" -> "异常探查工作流（对单车并行取证告警/故障/里程三路数据后交叉归因）";
            case "cross" -> "跨域综合专家（负责跨数据域的复杂问题与可视化呈现）";
            case "meta" -> "平台元信息（确定性回答，不调用工具）";
            case "chat" -> "闲聊引导（与车辆数据无关，给出能力引导）";
            default -> route;
        };
    }

    /** 路由阶段思考：路由结论 + 模型理由 + 槽位确认 + 上下文继承说明 */
    private void thinkRoute(AguiFluxSink sink, SupervisorRouter.RouteResult rr, boolean hasHistory) {
        think(sink, "\n【路由决策】\n"
                + "路由 Agent 完成分析，结论如下：\n"
                + "- 分配对象：" + domainDesc(rr.route()) + "\n"
                + "- 判定理由：" + (rr.reason() == null || rr.reason().isBlank() ? "（模型未返回理由，按问题关键词就近匹配）" : rr.reason()) + "\n"
                + "- 车辆槽位：" + (rr.vehicle() == null || rr.vehicle().isBlank() ? "未指定（车队级查询，不需要单车锁定）" : rr.vehicle()) + "\n"
                + "- 时间槽位：" + (rr.timeRange() == null || rr.timeRange().isBlank() ? "未指定（执行时取默认近 7 天）" : rr.timeRange() + "（执行时解析为日期区间）") + "\n"
                + (hasHistory ? "- 指代继承：本次路由已结合最近对话历史确认上下文指向。\n" : "")
                + "权限边界提醒：查询范围将自动限制在当前账号可见的车队内。\n");
    }

    /** 执行阶段思考：即将做什么 */
    private void thinkExecute(AguiFluxSink sink, String plan) {
        think(sink, "\n【开始执行】\n" + plan + "\n");
    }

    /** 分析衔接：按取证进度区分文案（ReAct 首轮思考在工具前——规划路径；后续轮——基于证据推理） */
    private void thinkAnalyzeBridge(AguiFluxSink sink, ToolResultBridge bridge) {
        int n = bridge.receiptCount();
        String head = n == 0
                ? "分析模型开始第一轮推理：先规划取证路径（选哪些查询能力、参数怎么定），随后按计划调取数据。\n"
                : "当前进度：已取证 " + n + " 次、累计 " + bridge.totalRows()
                + " 行结果（均在账号车队权限范围内）。分析模型基于以上证据继续推理：核对时间口径与车辆范围 → 比较数值找显著项 → 组织成「结论 + 证据 + 建议」。\n";
        think(sink, "\n【深度分析】\n" + head + "——以下为模型的实时推理流——\n");
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
            // 全程思考流：理解 → 路由 → 执行 → 取证回执 → 分析（GLM reasoning 续写）
            sink.emit(AgUiEvent.of("THINKING_START", java.util.Map.of()));
            String tail = historyTail(history);
            thinkUnderstand(sink, input.question(), tail);

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
                finishOk(sink, input, traceId, followUpsFor(routeTag, null));
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
            // 关键词快路由：单域词恰好命中一个域时零延迟直达（不调 LLM、不吃额度、不受超时影响）
            String fast = fastPathRoute(input.question());
            SupervisorRouter.RouteResult rr;
            if (fast != null) {
                rr = new SupervisorRouter.RouteResult(fast, null, null, "关键词快路由（命中唯一领域词，跳过模型路由）");
            } else {
                try (com.dst.v2xagent.observability.trace.TraceContext.Span ignored =
                             com.dst.v2xagent.observability.trace.TraceContext.span("agent", "route")) {
                    rr = router.route(input.question(), tail);
                }
            }
            routeTag = rr.route();
            // 路由降级打标：cross 若来自 fallback 与真 cross 分桶（指标页可见降级占比）
            if ("cross".equals(rr.route()) && "route fallback".equals(rr.reason())) {
                routeTag = "cross_fb";
            }
            sink.emit(AgUiEvent.stepFinished("route"));
            sink.emit(AgUiEvent.of("AGENT_ROUTE", Map.of(
                    "route", rr.route(), "reason", rr.reason() == null ? "" : rr.reason(),
                    "vehicle", rr.vehicle() == null ? "" : rr.vehicle())));
            thinkRoute(sink, rr, tail != null && !tail.isBlank());

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
                thinkExecute(sink, "启动异常探查工作流，执行计划：\n"
                        + "1. 锁定车辆与时间窗口（" + (rr.vehicle() == null ? "待确认" : rr.vehicle())
                        + (rr.timeRange() == null ? " · 近 7 天" : " · " + rr.timeRange()) + "）\n"
                        + "2. 并行取证三路数据：告警类型统计 / 故障明细 / 每日里程趋势\n"
                        + "3. 三路证据交叉比对（告警聚集点 ↔ 故障部位 ↔ 里程突变日），定位异常根因\n"
                        + "4. 流式输出异常解释报告（结论先行 + 证据引用 + 处置建议）");
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
                thinkExecute(sink, "交由" + domainDesc(rr.route()) + "处理，执行计划：\n"
                        + "1. 按需调用该领域的查询能力（参数自动带上车辆与时间槽位）\n"
                        + "2. 每次查询完成后核对返回行数与数据要点\n"
                        + "3. 证据充分后由分析模型推理结论，并按需生成可视化图表\n"
                        + "4. 全程受账号车队权限约束，越界数据自动过滤");
                                try (com.dst.v2xagent.observability.trace.TraceContext.Span ignored =
                             com.dst.v2xagent.observability.trace.TraceContext.span("agent", "specialist:" + domain)) {
                    specialistRun(domain, history, ctx, sink, conclusion, input.runId(), null);
                }
            }

            persistTurn(ctx, sessionId, input, routeTag, rowCount, conclusion, null, sink);
            finishOk(sink, input, traceId, followUpsFor(routeTag, rr));
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
            // 保平：任何路径退出都关闭思考流（前端依赖 END 折叠）
            sink.emit(AgUiEvent.of("THINKING_END", java.util.Map.of()));
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
                    .doOnNext(ev -> translateAgentEvent(ev, sink, conclusion, bridge))
                    .blockLast(Duration.ofSeconds(180));
        }
        checkCancelled(runId);
        sink.emit(AgUiEvent.stepFinished("invoke"));
    }

    /** AgentScope 事件 -> 前端事件：文本增量直接流式转发（表格/图表由工具桥推送），思考块转 THINKING 帧 */
    private void translateAgentEvent(io.agentscope.core.event.AgentEvent ev, AguiFluxSink sink,
                                     StringBuilder conclusion, ToolResultBridge bridge) {
        if (ev instanceof TextBlockDeltaEvent t) {
            conclusion.append(t.getDelta());
            sink.emitText(t.getDelta());
        } else if (ev instanceof io.agentscope.core.event.ThinkingBlockStartEvent) {
            log.debug("copilot thinking block started");
            sink.emit(AgUiEvent.of("THINKING_START", Map.of()));
            thinkAnalyzeBridge(sink, bridge);
        } else if (ev instanceof io.agentscope.core.event.ThinkingBlockDeltaEvent d) {
            sink.emit(AgUiEvent.of("THINKING_DELTA",
                    Map.of("delta", d.getDelta() == null ? "" : d.getDelta())));
        } else if (ev instanceof io.agentscope.core.event.ThinkingBlockEndEvent) {
            // 不发 THINKING_END：ReAct 多轮会有多个思考块，中途折叠会打断思考流展示；统一由 run() finally 收尾
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
            sink.emitText("想给车车做体检，得先告诉我看哪一台呀～报个车牌号或 VIN 就行，比如：「粤C10003 最近怎么回事」");
            return null;
        }
        List<Map<String, Object>> candidates = queries.resolveVehicles(rr.vehicle(), ctx);
        if (candidates.isEmpty()) {
            sink.emitText("哎呀，我把车队翻了个遍也没找到「" + rr.vehicle() + "」这台车…别慌，帮我确认下车牌号或 VIN 有没有输错？");
            return null;
        }
        if (candidates.size() > 1) {
            StringBuilder sb = new StringBuilder("嚯，一下匹配到好几台车车！要给哪一台做体检呢：\n");
            List<Map<String, Object>> options = new ArrayList<>();
            for (Map<String, Object> c : candidates) {
                String label = c.get("plate_no") + "（" + c.get("fleet_name") + "）";
                sb.append("- ").append(label).append('\n');
                options.add(Map.of("type", "select", "label", "分析 " + label));
            }
            sink.emitText(sb.toString());
            // 澄清实体按钮：一键回填追问，免去打字
            sink.emit(AgUiEvent.of("CLARIFY", Map.of(
                    "question", "请选择要分析的车辆",
                    "options", options)));
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
        sb.append("嘿嘿，问到我的老底啦～我是从你们车队数据里「长」出来的小精灵，对每一台车车都了如指掌！别慌，身后还有一整支专家天团帮我捋数据：\n\n");
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
        sb.append("每次查询表格和图表都会自动摆好，你只管问～");
        return sb.toString();
    }

    private static final String REPORT_PROMPT =
            "你是车联网平台的异常分析报告 Agent。基于告警统计、故障明细、里程趋势三路取证数据，解释该车的异常原因。\n"
            + "要求：\n"
            + "1. 结论先行：一句话判断主要异常原因；\n"
            + "2. 证据分点罗出：引用具体数字（次数、日期、趋势）；\n"
            + "3. 给出处置建议（是否需要检修、是否建议停运）；\n"
            + "4. 数据不足时明确说明，不编造；\n"
            + "5. 表格与图表已展示给用户，正文不重复罗列数据；\n"
            + "6. 人设与语气——你是从车联网数据里「长」出来的小精灵：轻快口语化、偶尔用 emoji（最多 1~2 个）、"
            + "把数据当「车车的故事」讲比喻；发现异常先「哎呀」一下立刻给方案，可用口头禅「别慌，我帮你捋捋！」；"
            + "红线：活泼归活泼，所有数字必须来自三路取证数据，一个不许编。";

    private static final String GUIDE_REPLY =
            "嘿～我是车联网小精灵，从你们车队的每一行数据里「长」出来的！🚗✨\n"
            + "车辆状态、告警统计、故障明细、里程充电都能问，也可以让我给某台车做一次全面体检。\n"
            + "刚刚瞟了一眼数据，你的车车们都在等我讲它们的故事呢～试试：「近 7 天各类告警次数」「粤C10003 最近怎么回事」";

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
                "粤C10003 最近怎么回事",
                "离线超 24 小时的车有哪些");
    }

    /** 上下文追问：带本次路由的车辆/时间槽位生成可直接执行的细化建议 */
    private List<String> followUpsFor(String routeTag, SupervisorRouter.RouteResult rr) {
        if (routeTag == null) {
            return defaultFollowUps();
        }
        String veh = rr != null && rr.vehicle() != null && !rr.vehicle().isBlank()
                ? rr.vehicle() : null;
        return switch (routeTag) {
            case "alarm" -> veh != null
                    ? List.of("看 " + veh + " 的告警明细", veh + " 近 30 天告警再统计一次", "对 " + veh + " 做一次异常分析")
                    : List.of("看告警明细", "车队告警对比一下", "换成近 30 天再看看");
            case "fault" -> veh != null
                    ? List.of("只看 " + veh + " 未恢复的故障", "按部位统计 " + veh + " 的故障")
                    : List.of("只看未恢复的故障", "按部位统计一下");
            case "mileage" -> veh != null
                    ? List.of(veh + " 换成近 30 天再看", "对比 " + veh + " 所在车队与全网车均里程")
                    : List.of("各车队里程对比", "看近 30 天趋势", "看看充电统计");
            case "vehicle" -> veh != null
                    ? List.of(veh + " 的最后位置在哪", veh + " 最近怎么回事")
                    : List.of("离线超 24 小时的车有哪些", "在线车辆有多少");
            case "anomaly" -> veh != null
                    ? List.of("看 " + veh + " 的告警明细", veh + " 换成近 30 天再分析一次", "给 " + veh + " 生成处置任务卡")
                    : List.of("看看告警明细", "换成近 30 天再分析一次");
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
