package com.dst.v2xagent.semantic;

import com.dst.v2xagent.capability.CapabilityService;
import com.dst.v2xagent.capability.model.CapabilityDefinition;
import com.dst.v2xagent.capability.model.TableResult;
import com.dst.v2xagent.common.PermissionContext;
import com.dst.v2xagent.observability.trace.TraceContext;
import com.dst.v2xagent.semantic.model.MetricDefinition;
import com.dst.v2xagent.semantic.model.SemanticQuery;
import com.dst.v2xagent.semantic.model.SemanticResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 语义查询服务：Semantic Query → QueryPlan → Capability 管线 → SemanticResult
 * 负责把 capability 表结果适配为指标语义（标量聚合 / 比率公式 / 维度行）。
 */
@Service
@RequiredArgsConstructor
public class SemanticQueryService {

    private final SemanticQueryPlanner planner;
    private final CapabilityService capabilityService;

    /** 执行语义查询（带 Trace span） */
    public SemanticResult execute(SemanticQuery query, PermissionContext ctx) {
        SemanticQueryPlanner.QueryPlan plan = planner.plan(query);
        try (TraceContext.Span span = TraceContext.span("semantic", plan.metric().getId())) {
            CapabilityService.InvokeOutcome outcome = capabilityService.invoke(plan.capabilityId(), plan.params(), ctx);
            return adapt(plan, outcome);
        }
    }

    /** 表结果 -> 指标语义：列定位、比率公式、标量聚合、维度行组装 */
    private SemanticResult adapt(SemanticQueryPlanner.QueryPlan plan, CapabilityService.InvokeOutcome outcome) {
        MetricDefinition metric = plan.metric();
        TableResult table = outcome.table();
        List<CapabilityDefinition.ColumnDef> columns = table.columns();
        List<Object[]> rawRows = table.rows();

        int valueIdx = metric.getValueColumn() == null ? -1 : columnIndex(columns, metric.getValueColumn());
        int numIdx = metric.getFormula() == null ? -1 : columnIndex(columns, metric.getFormula().getNumerator());
        int denIdx = metric.getFormula() == null ? -1 : columnIndex(columns, metric.getFormula().getDenominator());

        // 维度列定位
        Map<String, Integer> dimIdx = new LinkedHashMap<>();
        for (String dim : plan.dimensions()) {
            String col = metric.getDimensionColumns().get(dim);
            if (col != null) {
                dimIdx.put(dim, columnIndex(columns, col));
            }
        }

        // 逐行计算指标值
        List<Map<String, Object>> rows = new ArrayList<>();
        double sum = 0;
        long count = 0;
        double numSum = 0;
        double denSum = 0;
        for (Object[] raw : rawRows) {
            // count 模式：无取值列时每行计 1（offline_vehicle_list 等列表型指标）
            Double value = metric.getFormula() != null
                    ? ratio(toDouble(raw[numIdx]), toDouble(raw[denIdx]))
                    : (valueIdx < 0 ? 1.0 : toDouble(raw[valueIdx]));
            if (metric.getFormula() != null) {
                numSum += toDouble(raw[numIdx]);
                denSum += toDouble(raw[denIdx]);
            } else if (value != null) {
                sum += value;
            }
            count++;
            if (!dimIdx.isEmpty()) {
                Map<String, Object> row = new LinkedHashMap<>();
                dimIdx.forEach((dim, idx) -> row.put(dim, raw[idx]));
                row.put("value", value);
                rows.add(row);
            }
        }

        // 标量值：无维度查询时聚合
        Double scalar = null;
        if (dimIdx.isEmpty()) {
            if (metric.getFormula() != null) {
                scalar = ratio(numSum, denSum);
            } else {
                scalar = "count".equals(metric.getAggregate()) ? (double) count : sum;
            }
        }

        SemanticResult.Evidence evidence = new SemanticResult.Evidence(
                outcome.definition().getId(), outcome.definition().getVersion(),
                outcome.resolved().fingerprint(), outcome.table().stats() != null ? outcome.table().stats().elapsedMs() : 0,
                outcome.table().truncated(), outcome.table().freshness());
        return new SemanticResult(metric.getId(), metric.getName(), metric.getUnit(),
                plan.dimensions(), scalar, rows, evidence);
    }

    private int columnIndex(List<CapabilityDefinition.ColumnDef> columns, String name) {
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).getName().equals(name)) {
                return i;
            }
        }
        throw new IllegalStateException("结果集缺少列: " + name);
    }

    private Double toDouble(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        if (o == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 比率计算：分母为 0 / null 返回 null（不造数） */
    private Double ratio(Double numerator, Double denominator) {
        if (numerator == null || denominator == null || denominator == 0) {
            return null;
        }
        return numerator / denominator;
    }
}
