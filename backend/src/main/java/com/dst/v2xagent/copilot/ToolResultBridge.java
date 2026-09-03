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

    public ToolResultBridge(AguiFluxSink sink, RenderChartTool renderChartTool) {
        this.sink = sink;
        this.renderChartTool = renderChartTool;
    }

    /** 推送一次工具调用的完整事件链：START -> ARGS -> RESULT -> CHART_SPEC */
    public void emitOutcome(SimQueries.Outcome outcome, Map<String, Object> argsDisplay) {
        CapabilityDefinition def = outcome.def();
        TableResult table = outcome.table();
        int seq = callSeq.incrementAndGet();
        sink.emit(AgUiEvent.toolCallStart(def.getId() + "#" + seq, def.getDisplay()));
        Map<String, Object> args = new LinkedHashMap<>(argsDisplay);
        args.put("_editable", List.of());
        sink.emit(AgUiEvent.toolCallArgs(args));
        sink.emit(AgUiEvent.of("TOOL_CALL_RESULT", tablePayload(def, table)));
        if (table.rowCount() > 0) {
            RenderChartTool.ChartSpec chart = renderChartTool.render(def, table);
            sink.emit(AgUiEvent.of("CHART_SPEC", Map.of(
                    "chartType", chart.chartType(),
                    "spec", chart.spec(),
                    "chartAlternatives", chart.chartAlternatives(),
                    "notes", chart.notes())));
        }
    }

    /** 推送统一可视化帧（UI Schema，前端 VisualizationRenderer 按 renderer 分发） */
    public void emitVisSpec(Map<String, Object> uiSchema) {
        sink.emit(AgUiEvent.of("VIS_SPEC", uiSchema));
    }

    /** 表格载荷：与旧链路前端契约一致 */
    private Map<String, Object> tablePayload(CapabilityDefinition def, TableResult table) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("capabilityId", def.getId());
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
