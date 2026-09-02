package com.dst.v2xagent.runtime;

import com.dst.v2xagent.agui.AgUiEvent;
import com.dst.v2xagent.capability.CapabilityRegistry;
import com.dst.v2xagent.capability.CapabilitySelector;
import com.dst.v2xagent.capability.CapabilityExecutor;
import com.dst.v2xagent.capability.ParamResolver;
import com.dst.v2xagent.capability.RenderChartTool;
import com.dst.v2xagent.capability.VehicleResolver;
import com.dst.v2xagent.capability.model.CapabilityDefinition;
import com.dst.v2xagent.capability.model.TableResult;
import com.dst.v2xagent.common.ApiException;
import com.dst.v2xagent.common.PermissionContext;
import com.dst.v2xagent.llm.LlmClient;
import com.dst.v2xagent.memory.SessionStore;
import com.dst.v2xagent.orchestration.OrchestrationExecutor;
import com.dst.v2xagent.memory.ShortTermMemory;
import com.dst.v2xagent.runtime.model.CapabilitySelection;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 主链路编排器（显式状态机，「确定性主链路」的定义）
 * 节点顺序由代码固定：Understand → Resolve → Invoke → Render → Conclude → Persist
 * LLM 只在 Understanding（抽取）与 Concluding（措辞）两个节点参与。
 *
 * 事件顺序红线：TOOL_CALL_RESULT（表格）必须先于 TEXT_MESSAGE_CONTENT（结论）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RunOrchestrator {

    private final AgentProfileRegistry profileRegistry;
    private final CapabilityRegistry capabilityRegistry;
    private final CapabilitySelector selector;
    private final ParamResolver paramResolver;
    private final CapabilityExecutor executor;
    private final RenderChartTool renderChartTool;
    private final LlmClient llmClient;
    private final SessionStore sessionStore;
    private final ShortTermMemory shortTermMemory;
    private final RunAuditRepository auditRepository;
    private final OrchestrationExecutor orchestrationExecutor;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 运行取消标记 */
    private final Map<String, Boolean> cancelFlags = new ConcurrentHashMap<>();

    /** 一次运行的输入 */
    public record RunInput(String threadId, String runId, String profileId, String question,
                           Map<String, Object> forwardedProps) {}

    public void cancel(String runId) {
        cancelFlags.put(runId, Boolean.TRUE);
    }

    /**
     * 执行一次运行（在受限并发的虚拟线程上调用）。
     */
    public void run(RunInput input, PermissionContext ctx, RunEventSink sink) {
        long start = System.currentTimeMillis();
        String traceId = UUID.randomUUID().toString().replace("-", "");
        String status = "SUCCESS";
        String errorCode = null;
        String failedStage = null;
        String capabilityId = null;
        Integer rowCount = null;
        Map<String, Object> normalizedParams = null;
        StringBuilder conclusion = new StringBuilder();

        AgentProfileRegistry.AgentProfile profile = profileRegistry.get(input.profileId());
        String sessionId = sessionStore.resolveSessionId(ctx.tenantId(), input.threadId(), ctx.userId(), profile.getId());

        try {
            sink.emit(AgUiEvent.runStarted(input.runId(), input.threadId(), traceId));

            // ── Understand：裁剪 catalog → 意图/槽位抽取 ──
            // 闲聊门样：问候/无语义输入不进 capability 路由（确定性规则，先于 LLM）
            String smallTalk = smallTalkReply(input.question());
            if (smallTalk != null) {
                sink.emitText(smallTalk);
                sink.emit(AgUiEvent.of("RUN_FINISHED", Map.of("runId", input.runId(),
                        "followUps", java.util.List.of(
                                "今天车队在线率怎么样",
                                "近 7 天各类型告警次数",
                                "各车队近 7 天里程对比"))));
                status = "SUCCESS";
                return;
            }

            sink.emit(AgUiEvent.stepStarted("understand", "正在理解你的问题"));
            checkCancelled(input.runId());
            ShortTermMemory.QueryIntent prevIntent = shortTermMemory.loadIntent(sessionId).orElse(null);
            List<CapabilityDefinition> candidates = selector.select(
                    input.question(), ctx, prevIntent != null ? prevIntent.capabilityId() : null);
            // profile 域裁剪
            candidates = candidates.stream()
                    .filter(c -> profile.getCapabilityDomains().isEmpty() || profile.getCapabilityDomains().contains(c.getDomain()))
                    .toList();

            CapabilitySelection selection = llmClient.extract(input.question(), candidates);
            selection = inheritFromContext(selection, prevIntent, input.question());
            sink.emit(AgUiEvent.stepFinished("understand"));

            // 判定（纯 Java 决策表，不交给模型自由决定）
            if (selection.capabilityId() == null || selection.confidence() < 0.45) {
                status = "REFUSED";
                failedStage = "understand";
                emitRefuse(sink, candidates,
                        "这个问题超出了我目前的能力范围。已记录到「未覆盖问题榜」，你也可以提交能力需求。");
                sink.emit(AgUiEvent.of("RUN_FINISHED", Map.of("runId", input.runId(), "followUps", List.of())));
                return;
            }
            if (!selection.missing().isEmpty()) {
                status = "REFUSED";
                failedStage = "clarify";
                emitClarifyForMissing(sink, selection);
                sink.emit(AgUiEvent.of("RUN_FINISHED", Map.of("runId", input.runId(), "followUps", List.of())));
                return;
            }

            CapabilityDefinition def = capabilityRegistry.require(selection.capabilityId());
            capabilityId = def.getId();
            sink.emit(AgUiEvent.toolCallStart(def.getId(), def.getDisplay()));

            // ── Resolve：八步参数解析（含权限注入）──
            sink.emit(AgUiEvent.stepStarted("resolve", "正在解析参数"));
            checkCancelled(input.runId());
            ParamResolver.ResolvedParams resolved;
            try {
                resolved = paramResolver.resolve(def, selection.params(), ctx, ZonedDateTime.now());
            } catch (ParamResolver.MissingParamException e) {
                status = "REFUSED";
                failedStage = "clarify";
                emitClarifyForMissing(sink, selection);
                sink.emit(AgUiEvent.of("RUN_FINISHED", Map.of("runId", input.runId(), "followUps", List.of())));
                return;
            } catch (ParamResolver.AmbiguousVehicleException e) {
                status = "REFUSED";
                failedStage = "clarify";
                emitClarifyForVehicles(sink, def, selection, e.candidates);
                sink.emit(AgUiEvent.of("RUN_FINISHED", Map.of("runId", input.runId(), "followUps", List.of())));
                return;
            }
            normalizedParams = displayParams(resolved);
            // 附加可编辑参数白名单（schema 声明的 key），供前端参数微调过滤展示字段
            Map<String, Object> argsFrame = new LinkedHashMap<>(normalizedParams);
            List<String> editableKeys = def.getParams() == null ? List.of()
                    : def.getParams().stream().map(CapabilityDefinition.ParamDef::getName).toList();
            argsFrame.put("_editable", editableKeys);
            sink.emit(AgUiEvent.toolCallArgs(argsFrame));
            sink.emit(AgUiEvent.stepFinished("resolve"));

            // P1 编排路径：预定义模板多 capability 取证
            if ("orchestration".equals(def.getKind())) {
                sink.emit(AgUiEvent.stepStarted("invoke", "正在多源取证"));
                checkCancelled(input.runId());
                Map<String, Object> orchInput = new HashMap<>(selection.params());
                orchInput.putIfAbsent("time_range", "近7天");
                OrchestrationExecutor.OrchestrationOutcome outcome =
                        orchestrationExecutor.execute(def.getId(), orchInput, ctx);

                // 主表：表格先于结论（红线不变）
                OrchestrationExecutor.StepOutcome primary =
                        outcome.steps().get(outcome.template().getOutput().getPrimaryTable());
                TableResult table = primary.table();
                rowCount = table.rowCount();
                sink.emit(AgUiEvent.of("TOOL_CALL_RESULT", tablePayload(def, table)));

                // 图：chart 步骤（失败则跳过不出图）
                OrchestrationExecutor.StepOutcome chartStep =
                        outcome.steps().get(outcome.template().getOutput().getChart());
                if (chartStep != null && chartStep.ok()) {
                    CapabilityDefinition chartDef = capabilityRegistry.require(chartStep.capabilityId());
                    RenderChartTool.ChartSpec chart = renderChartTool.render(chartDef, chartStep.table());
                    sink.emit(AgUiEvent.of("CHART_SPEC", Map.of(
                            "chartType", chart.chartType(),
                            "spec", chart.spec(),
                            "chartAlternatives", chart.chartAlternatives(),
                            "notes", chart.notes())));
                }
                sink.emit(AgUiEvent.stepFinished("invoke"));

                // 结论：多步汇总 + 缺失声明
                sink.emit(AgUiEvent.stepStarted("conclude", "正在生成结论"));
                checkCancelled(input.runId());
                String orchTimeDisplay = resolved.timeRange() != null ? resolved.timeRange().display() : null;
                llmClient.streamOrchestrationConclusion(input.question(), def.getDisplay(),
                        outcome.steps(), outcome.missing(), orchTimeDisplay, delta -> {
                            conclusion.append(delta);
                            sink.emitText(delta);
                        });
                sink.emit(AgUiEvent.stepFinished("conclude"));

                ShortTermMemory.QueryIntent intent = new ShortTermMemory.QueryIntent(
                        def.getId(), selection.params(), resolved.resolvedVins(), ZonedDateTime.now().toString());
                shortTermMemory.saveIntent(sessionId, intent);
                sink.emit(AgUiEvent.of("RUN_FINISHED", Map.of(
                        "runId", input.runId(), "traceId", traceId,
                        "followUps", java.util.List.of("换成上周再看看", "那前天呢"))));
                String orchParamsJson = mapper.writeValueAsString(selection.params());
                sessionStore.appendTurn(ctx.tenantId(), sessionId, input.question(), def.getId(),
                        orchParamsJson, rowCount, conclusion.toString(), summarize(conclusion.toString()));
                return;
            }

            // ── Invoke：执行查询（护栏 + 熔断 + 缓存）──
            sink.emit(AgUiEvent.stepStarted("invoke", "正在调用「" + def.getDisplay() + "」"));
            checkCancelled(input.runId());
            TableResult table = executor.execute(def, resolved, ctx);
            rowCount = table.rowCount();

            // 表格结果必须先于结论文本（信任感红线）
            sink.emit(AgUiEvent.of("TOOL_CALL_RESULT", tablePayload(def, table)));
            sink.emit(AgUiEvent.stepFinished("invoke"));

            // ── Render：出图（后端只出 ChartSpec）──
            RenderChartTool.ChartSpec chart = renderChartTool.render(def, table);
            sink.emit(AgUiEvent.of("CHART_SPEC", Map.of(
                    "chartType", chart.chartType(),
                    "spec", chart.spec(),
                    "chartAlternatives", chart.chartAlternatives(),
                    "notes", chart.notes())));

            // ── Conclude：结论生成（流式，只基于返回数据）──
            sink.emit(AgUiEvent.stepStarted("conclude", "正在生成结论"));
            checkCancelled(input.runId());
            String timeDisplay = resolved.timeRange() != null ? resolved.timeRange().display() : null;
            llmClient.streamConclusion(input.question(), def, table, timeDisplay, delta -> {
                conclusion.append(delta);
                sink.emitText(delta);
            });
            sink.emit(AgUiEvent.stepFinished("conclude"));

            // ── Persist：会话轮次 + 短记忆意图 + 追问建议 ──
            ShortTermMemory.QueryIntent intent = new ShortTermMemory.QueryIntent(
                    def.getId(), selection.params(), resolved.resolvedVins(), ZonedDateTime.now().toString());
            shortTermMemory.saveIntent(sessionId, intent);
            List<String> followUps = buildFollowUps(def, selection);
            sink.emit(AgUiEvent.of("RUN_FINISHED", Map.of(
                    "runId", input.runId(), "traceId", traceId, "followUps", followUps)));

            String paramsJson = mapper.writeValueAsString(selection.params());
            sessionStore.appendTurn(ctx.tenantId(), sessionId, input.question(), def.getId(),
                    paramsJson, rowCount, conclusion.toString(), summarize(conclusion.toString()));

        } catch (CancelledException e) {
            status = "CANCELLED";
            log.info("run {} 已取消", input.runId());
        } catch (ApiException e) {
            status = "REFUSED".equals(status) ? status : "FAILED";
            errorCode = e.errorCode();
            failedStage = e.failedStage();
            if ("SCOPE_DENIED".equals(e.errorCode())) {
                emitRefuse(sink, List.of(), e.getMessage());
                sink.emit(AgUiEvent.of("RUN_FINISHED", Map.of("runId", input.runId(), "followUps", List.of())));
            } else {
                sink.emit(AgUiEvent.runError(e.errorCode(), e.failedStage(), e.retryable(), e.getMessage()));
            }
        } catch (Exception e) {
            status = "FAILED";
            errorCode = "INTERNAL_ERROR";
            log.error("run {} 执行异常", input.runId(), e);
            sink.emit(AgUiEvent.runError("INTERNAL_ERROR", "unknown", true, "系统内部错误，请稍后重试"));
        } finally {
            cancelFlags.remove(input.runId());
            auditRepository.record(ctx, input, traceId, capabilityId, normalizedParams,
                    status, errorCode, failedStage, System.currentTimeMillis() - start, rowCount);
            sink.close();
        }
    }

    /** 多轮参数继承：「那前天呢」继承上一轮 capability；「还是刚才那台车」继承 vin_list */
    private CapabilitySelection inheritFromContext(CapabilitySelection selection,
                                                   ShortTermMemory.QueryIntent prev, String question) {
        if (prev == null) return selection;
        boolean refersPrevious = question.contains("那") || question.contains("刚才") || question.contains("这台车")
                || question.contains("那台") || question.contains("它");
        if (selection.capabilityId() == null && refersPrevious) {
            Map<String, Object> merged = new HashMap<>(prev.params());
            merged.putAll(selection.params());
            return new CapabilitySelection(prev.capabilityId(), merged, 0.9, List.of(), List.of());
        }
        if (selection.capabilityId() != null && refersPrevious && prev.resolvedVins() != null
                && !prev.resolvedVins().isEmpty() && !selection.params().containsKey("vehicle")
                && !selection.params().containsKey("vin_list")) {
            Map<String, Object> merged = new HashMap<>(selection.params());
            merged.put("vin_list", prev.resolvedVins());
            return new CapabilitySelection(selection.capabilityId(), merged, selection.confidence(),
                    selection.missing(), selection.alternatives());
        }
        return selection;
    }

    /** 缺必填参数 → 澄清气泡（选项按钮携带完整参数，点一下即重算） */
    private void emitClarifyForMissing(RunEventSink sink, CapabilitySelection selection) {
        List<Map<String, Object>> options = new ArrayList<>();
        if (selection.missing().contains("time_range")) {
            for (String label : List.of("今天", "近7天", "近30天")) {
                Map<String, Object> params = new HashMap<>(selection.params());
                params.put("time_range", label);
                options.add(Map.of("label", label, "capabilityId", selection.capabilityId(), "params", params));
            }
            sink.emit(AgUiEvent.clarify("你想看哪个时间范围？", options));
        } else if (selection.missing().contains("vehicle")) {
            sink.emit(AgUiEvent.clarify("请告诉我要查哪台车（车牌或 VIN）：", List.of(
                    Map.of("label", "输入车牌/VIN", "type", "input", "capabilityId", selection.capabilityId(),
                            "params", selection.params()))));
        } else {
            sink.emit(AgUiEvent.clarify("还缺少信息：" + String.join("、", selection.missing()), options));
        }
    }

    /** 车辆多命中 → 澄清气泡（车牌 + VIN 后 6 位 + 车队） */
    private void emitClarifyForVehicles(RunEventSink sink, CapabilityDefinition def,
                                        CapabilitySelection selection,
                                        List<VehicleResolver.VehicleCandidate> candidates) {
        List<Map<String, Object>> options = new ArrayList<>();
        for (VehicleResolver.VehicleCandidate c : candidates) {
            Map<String, Object> params = new HashMap<>(selection.params());
            params.remove("vehicle");
            params.put("vin_list", List.of(c.vin()));
            String vinTail = c.vin().length() > 6 ? c.vin().substring(c.vin().length() - 6) : c.vin();
            options.add(Map.of(
                    "label", c.plateNo() + "（" + c.fleetName() + " · VIN 尾号 " + vinTail + "）",
                    "capabilityId", def.getId(), "params", params));
        }
        sink.emit(AgUiEvent.clarify("找到多台匹配车辆，请选择：", options));
    }

    private void emitRefuse(RunEventSink sink, List<CapabilityDefinition> candidates, String message) {
        List<Map<String, String>> suggested = candidates.stream().limit(3)
                .map(c -> Map.of("id", c.getId(), "display", c.getDisplay())).toList();
        sink.emit(AgUiEvent.refuse("CAPABILITY_NOT_COVERED", message, suggested));
    }

    /** 追问建议：基于当前参数变形生成（本地模板，零 LLM 成本） */
    private List<String> buildFollowUps(CapabilityDefinition def, CapabilitySelection selection) {
        List<String> ups = new ArrayList<>();
        if (selection.params().containsKey("time_range")) {
            ups.add("换成上周再看看");
            ups.add("那前天呢");
        }
        switch (def.getId()) {
            case "alarm_count_by_type" -> ups.add("看看告警明细");
            case "alarm_list" -> ups.add("按类型统计一下");
            case "mileage_daily" -> ups.add("各车队对比一下");
            case "mileage_by_fleet" -> ups.add("看看每日里程趋势");
            case "vehicle_online_status" -> ups.add("它的最后位置在哪");
            default -> {}
        }
        return ups.stream().limit(3).toList();
    }

    /** 闲聊识别：命中问候/闲聊词表或无实义输入时返回引导回复，否则返回 null */
    private static final java.util.Set<String> CHITCHAT = java.util.Set.of(
            "你好", "您好", "hi", "hello", "hey",
            "在吗", "在么", "嗨", "喂", "在",
            "谢谢", "感谢", "拜拜", "再见",
            "早上好", "上午好", "下午好", "晚上好", "晚安",
            "测试", "test",
            "你是谁", "你能干什么", "你能做什么",
            "你会什么", "帮助", "help");

    private static final String GUIDE_REPLY =
            "你好！我是设备运营 Agent。可以问我车辆在线率、告警统计、里程充电等数据问题，也可以直接让我生成图表。试试下面的问题：";

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

    /** 回显参数：归一化后的参数 + 解析出的绝对时间区间 */
    private Map<String, Object> displayParams(ParamResolver.ResolvedParams resolved) {
        Map<String, Object> display = new LinkedHashMap<>();
        resolved.bindParams().forEach((k, v) -> {
            if (k.startsWith("acl_")) return; // 权限参数不回显
            display.put(k, v instanceof java.time.LocalDateTime t
                    ? t.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) : v);
        });
        if (resolved.timeRange() != null) {
            display.put("time_range_display", resolved.timeRange().display());
            display.put("time_note", resolved.timeRange().note());
        }
        return display;
    }

    /** 表格载荷：columns（含语义/单位/对齐）+ rows + freshness + 执行统计 */
    private Map<String, Object> tablePayload(CapabilityDefinition def, TableResult table) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("capabilityId", def.getId());
        p.put("columns", table.columns());
        p.put("rows", table.rows().stream().map(r -> {
            List<Object> row = new ArrayList<>();
            for (Object cell : r) row.add(cell == null ? null : cell.toString());
            return row;
        }).toList());
        p.put("rowCount", table.rowCount());
        p.put("truncated", table.truncated());
        p.put("freshness", Map.of(
                "dataAsOf", table.freshness().dataAsOf(),
                "policyType", table.freshness().policyType(),
                "expectedDelayMin", table.freshness().expectedDelayMin()));
        p.put("stats", Map.of(
                "elapsedMs", table.stats().elapsedMs(),
                "scannedRows", table.stats().scannedRows(),
                "sqlSnapshot", table.stats().sqlSnapshot()));
        return p;
    }

    private String summarize(String conclusion) {
        if (conclusion == null) return null;
        return conclusion.length() > 200 ? conclusion.substring(0, 200) : conclusion;
    }

    private void checkCancelled(String runId) {
        if (cancelFlags.containsKey(runId)) throw new CancelledException();
    }

    /** 运行取消信号 */
    private static class CancelledException extends RuntimeException {}
}
