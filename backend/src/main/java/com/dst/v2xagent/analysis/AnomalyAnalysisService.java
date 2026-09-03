package com.dst.v2xagent.analysis;

import com.dst.v2xagent.analysis.model.AnalysisReport;
import com.dst.v2xagent.analysis.model.Evidence;
import com.dst.v2xagent.common.PermissionContext;
import com.dst.v2xagent.observability.trace.TraceContext;
import com.dst.v2xagent.semantic.SemanticQueryService;
import com.dst.v2xagent.semantic.model.SemanticQuery;
import com.dst.v2xagent.semantic.model.SemanticResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 异常分析服务（Analysis Engine）
 * 流程：Data(语义查询) → Normalize → Baseline → Compare → DetectAnomaly → Correlation → Evidence
 * 证据由本引擎生成，LLM 只负责解释证据，不制造证据。
 */
@Service
@RequiredArgsConstructor
public class AnomalyAnalysisService {

    private final SemanticQueryService semanticQueryService;

    /** 异常阈值（|变化百分比|） */
    private static final double THRESHOLD_PCT = BaselineAnalyzer.DEFAULT_THRESHOLD_PCT;

    /**
     * 执行异常分析：告警 / 故障 / 里程 三路取证 + 里程出充电相关性。
     * @param filters  业务过滤（vehicle / vin_list，可空；权限由管线注入）
     * @param ctx      权限上下文
     */
    public AnalysisReport analyze(Map<String, Object> filters, PermissionContext ctx) {
        try (TraceContext.Span span = TraceContext.span("analysis", "anomaly")) {
            Map<String, Object> safeFilters = filters == null ? Map.of() : filters;
            List<Evidence> evidences = new ArrayList<>();

            // 路径 1：告警本周 vs 上周
            evidences.add(compare("alarm.count", "本周", "上周", safeFilters, ctx));
            // 路径 2：故障本周 vs 上周
            evidences.add(compare("fault.count", "本周", "上周", safeFilters, ctx));
            // 路径 3：里程近 14 天前后半对比
            evidences.add(compareSeries("mileage.daily", "近14天", safeFilters, ctx));

            // 相关性：里程 x 充电（近 14 天日序列对齐）
            List<Map<String, Object>> correlations = new ArrayList<>();
            SemanticResult mileage = series("mileage.daily", safeFilters, ctx);
            SemanticResult charge = series("charge.daily", safeFilters, ctx);
            if (mileage != null && charge != null) {
                Double r = CorrelationAnalyzer.pearson(
                        valuesOf(mileage), valuesOf(charge));
                Map<String, Object> corr = new LinkedHashMap<>();
                corr.put("metric_a", "mileage.daily");
                corr.put("metric_b", "charge.daily");
                corr.put("pearson", r);
                corr.put("strength", CorrelationAnalyzer.strength(r));
                correlations.add(corr);
            }

            long anomalyCount = evidences.stream().filter(Evidence::anomaly).count();
            return new AnalysisReport(
                    subjectOf(safeFilters), "本周 / 近14天", "上周 / 前 7 天",
                    evidences, correlations, anomalyCount);
        }
    }

    /** 标量对比：当期 vs 基线（两次语义查询） */
    private Evidence compare(String metric, String currentRange, String baselineRange,
                             Map<String, Object> filters, PermissionContext ctx) {
        SemanticResult current = semanticQueryService.execute(
                new SemanticQuery(metric, List.of(), filters, currentRange), ctx);
        SemanticResult baseline = semanticQueryService.execute(
                new SemanticQuery(metric, List.of(), filters, baselineRange), ctx);
        return toEvidence(current, current.value(), baseline.value());
    }

    /** 序列对比：后半段均值 vs 前半段均值 */
    private Evidence compareSeries(String metric, String range,
                                   Map<String, Object> filters, PermissionContext ctx) {
        SemanticResult result = semanticQueryService.execute(
                new SemanticQuery(metric, List.of("day"), filters, range), ctx);
        List<Double> values = valuesOf(result);
        if (values.size() < 2) {
            return toEvidence(result, null, null);
        }
        int mid = values.size() / 2;
        Double baseline = BaselineAnalyzer.mean(values.subList(0, mid));
        Double current = BaselineAnalyzer.mean(values.subList(mid, values.size()));
        return toEvidence(result, current, baseline);
    }

    /** 日序列查询（相关性用，失败不影响主路径） */
    private SemanticResult series(String metric, Map<String, Object> filters, PermissionContext ctx) {
        try {
            return semanticQueryService.execute(
                    new SemanticQuery(metric, List.of("day"), filters, "近14天"), ctx);
        } catch (Exception e) {
            return null;
        }
    }

    /** 结果行提取数值序列 */
    private List<Double> valuesOf(SemanticResult result) {
        List<Double> values = new ArrayList<>();
        for (Map<String, Object> row : result.rows()) {
            Object v = row.get("value");
            values.add(v instanceof Number n ? n.doubleValue() : null);
        }
        return values;
    }

    private Evidence toEvidence(SemanticResult result, Double current, Double baseline) {
        Double pct = BaselineAnalyzer.pctChange(current, baseline);
        return new Evidence(result.metric(), result.metricName(), result.unit(),
                current, baseline, pct, BaselineAnalyzer.isAnomaly(pct, THRESHOLD_PCT),
                result.evidence().capabilityId() + "#" + result.evidence().queryFingerprint().substring(0, 12));
    }

    private String subjectOf(Map<String, Object> filters) {
        Object vehicle = filters.get("vehicle");
        return vehicle == null ? "当前车队" : String.valueOf(vehicle);
    }
}
