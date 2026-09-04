package com.dst.v2xagent.copilot;

import com.dst.v2xagent.agentscope.AguiFluxSink;
import com.dst.v2xagent.agui.AgUiEvent;
import com.dst.v2xagent.capability.RenderChartTool;
import com.dst.v2xagent.capability.model.CapabilityDefinition;
import com.dst.v2xagent.capability.model.TableResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工具结果事件桥（每次运行一个实例）。
 * 工具方法拿到查询结果后，通过本桥把「表格 + 图表」推给前端，
 * 模型只收到紧凑的摘要 JSON（避免大表占满上下文）。
 * 顺序红线不变：TOOL_CALL_RESULT 先于结论文本。
 */
public class ToolResultBridge {

    private final AguiFluxSink sink;
    private final RenderChartTool renderChartTool;
    private final AtomicInteger callSeq = new AtomicInteger(0);
    /** 取证回执统计（思考流「深度分析」汇总段用） */
    private int receiptCount = 0;
    private long totalRows = 0;

    /** 本轮工具帧记录（save_task 固化任务用）：与前端 tools 帧同构 {id,name,args} */
    public record FrameRecord(String id, String name, Map<String, Object> args) {}

    /** 帧日志（emitOutcome 顺序追加；save_task 工具读取，不含 save_task 自身） */
    private final List<FrameRecord> frameLog = new ArrayList<>();

    public ToolResultBridge(AguiFluxSink sink, RenderChartTool renderChartTool) {
        this.sink = sink;
        this.renderChartTool = renderChartTool;
    }

    /** 已完成的取证次数 */
    public int receiptCount() {
        return receiptCount;
    }

    /** 累计返回行数 */
    public long totalRows() {
        return totalRows;
    }

    /** 本轮工具帧快照（save_task 固化用，复制防止外部修改） */
    public List<FrameRecord> frames() {
        return List.copyOf(frameLog);
    }

    /** 推送一次工具调用的完整事件链：取证回执思考 -> START -> ARGS -> RESULT -> CHART_SPEC（全链路带能力/工具标注） */
    public void emitOutcome(SimQueries.Outcome outcome, Map<String, Object> argsDisplay) {
        emitOutcome(outcome, argsDisplay, null);
    }

    /**
     * 同上，带工具名标注：toolName 为 copilot 工具 id（如 count_alarms_by_type），
     * 与 capability 目录 id（def.id）是两套体系，帧上同时携带便于前端归因。
     */
    public void emitOutcome(SimQueries.Outcome outcome, Map<String, Object> argsDisplay, String toolName) {
        CapabilityDefinition def = outcome.def();
        TableResult table = outcome.table();
        int seq = callSeq.incrementAndGet();
        receiptCount++;
        totalRows += table.rowCount();
        frameLog.add(new FrameRecord(def.getId() + "#" + seq, def.getDisplay(), argsDisplay));
        emitReceiptThought(def, table, argsDisplay, toolName);
        Map<String, Object> start = new LinkedHashMap<>();
        start.put("capabilityId", def.getId() + "#" + seq);
        start.put("displayName", def.getDisplay());
        if (toolName != null) {
            start.put("toolName", toolName);
            start.put("toolDisplay", CopilotToolCatalog.displayOf(toolName));
        }
        sink.emit(AgUiEvent.of("TOOL_CALL_START", start));
        Map<String, Object> args = new LinkedHashMap<>(argsDisplay);
        args.put("_editable", List.of());
        sink.emit(AgUiEvent.toolCallArgs(args));
        sink.emit(AgUiEvent.of("TOOL_CALL_RESULT", tablePayload(def, table)));
        if (table.rowCount() > 0) {
            RenderChartTool.ChartSpec chart = renderChartTool.render(def, table);
            Map<String, Object> chartPayload = new LinkedHashMap<>();
            chartPayload.put("chartType", chart.chartType());
            chartPayload.put("spec", chart.spec());
            chartPayload.put("chartAlternatives", chart.chartAlternatives());
            chartPayload.put("notes", chart.notes());
            chartPayload.put("capabilityId", def.getId());
            chartPayload.put("capabilityDisplay", def.getDisplay());
            if (toolName != null) {
                chartPayload.put("toolName", toolName);
            }
            sink.emit(AgUiEvent.of("CHART_SPEC", chartPayload));
        }
    }

    /**
     * 取证回执思考注入：每次工具调用向思考流推送一段真实回执
     * （调用了什么能力/工具、参数是什么、读到了几行、数据要点是什么）。
     */
    private void emitReceiptThought(CapabilityDefinition def, TableResult table,
                                    Map<String, Object> argsDisplay, String toolName) {
        try {
            StringBuilder sb = new StringBuilder("\n【取证回执 · ").append(def.getDisplay()).append("】");
            if (toolName != null) {
                sb.append(" 工具 ").append(toolName);
            }
            sb.append('\n');
            String argsBrief = argsDisplay == null ? "" : argsDisplay.entrySet().stream()
                    .filter(e -> e.getKey() != null && !e.getKey().startsWith("_")
                            && e.getValue() != null && !String.valueOf(e.getValue()).isBlank())
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .reduce((a, b) -> a + "，" + b).orElse("");
            if (!argsBrief.isBlank()) {
                sb.append("· 调用参数：").append(argsBrief).append('\n');
            }
            sb.append("· 返回 ").append(table.rowCount()).append(" 行");
            if (table.stats() != null) {
                if (table.stats().scannedRows() > 0) {
                    sb.append("（扫描 ").append(String.format("%,d", table.stats().scannedRows())).append(" 行原始数据）");
                }
                sb.append("，耗时 ").append(table.stats().elapsedMs()).append("ms");
            }
            sb.append('\n');
            sb.append("· 数据要点：").append(topInsight(table)).append('\n');
            sink.emit(AgUiEvent.of("THINKING_DELTA", Map.of("delta", sb.toString())));
        } catch (Exception e) {
            // 回执思考失败不影响主链路
        }
    }

    /**
     * 从表格前几行提取要点：category 列当标签 + metric 列当度量。
     * 空结果 / 无 metric 列时给出兜底说明。
     */
    private String topInsight(TableResult table) {
        try {
            if (table.rowCount() == 0 || table.columns().isEmpty()) {
                return "结果为空——当前时间窗口内没有数据，必要时换时间范围或确认车辆存在";
            }
            int labelIdx = -1;
            int metricIdx = -1;
            for (int i = 0; i < table.columns().size(); i++) {
                String sem = table.columns().get(i).getSemantic();
                if (labelIdx < 0 && "category".equals(sem)) {
                    labelIdx = i;
                } else if (metricIdx < 0 && "metric".equals(sem)) {
                    metricIdx = i;
                }
            }
            if (labelIdx < 0) {
                labelIdx = 0;
            }
            if (metricIdx < 0) {
                // 没有数值列时只报首行标签
                Object first = table.rows().get(0)[labelIdx];
                return "首行「" + first + "」，共 " + table.rowCount() + " 条记录";
            }
            StringBuilder sb = new StringBuilder();
            int limit = Math.min(3, table.rowCount());
            for (int r = 0; r < limit; r++) {
                Object label = table.rows().get(r)[labelIdx];
                Object value = table.rows().get(r)[metricIdx];
                String unit = table.columns().get(metricIdx).getUnit();
                if (r > 0) {
                    sb.append("、");
                }
                sb.append(label).append(' ').append(value).append(unit == null ? "" : unit);
            }
            if (table.rowCount() > limit) {
                sb.append(" 等（按序前 ").append(limit).append(" 项）");
            }
            return sb.toString();
        } catch (Exception e) {
            return "共 " + table.rowCount() + " 条记录";
        }
    }

    /** 推送统一可视化帧（UI Schema，前端 VisualizationRenderer 按 renderer 分发） */
    public void emitVisSpec(Map<String, Object> uiSchema) {
        sink.emit(AgUiEvent.of("VIS_SPEC", uiSchema));
    }

    /** 表格载荷：与旧链路前端契约一致（带能力标注） */
    private Map<String, Object> tablePayload(CapabilityDefinition def, TableResult table) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("capabilityId", def.getId());
        p.put("capabilityDisplay", def.getDisplay());
        p.put("columns", table.columns());
        p.put("rows", table.rows().stream().map(r -> {
            List<Object> row = new ArrayList<>();
            for (Object cell : r) {
                row.add(cell == null ? null : cell.toString());
            }
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
}
