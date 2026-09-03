package com.dst.v2xagent.semantic;

import com.dst.v2xagent.common.ApiException;
import com.dst.v2xagent.semantic.model.MetricDefinition;
import com.dst.v2xagent.semantic.model.SemanticQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 语义查询规划器（Query Planner）
 * Semantic Query → Metric Resolver → Dimension Resolver → Capability Resolver → QueryPlan
 * 确定性 Java 逻辑，模型从不接触 SQL。
 */
@Component
@RequiredArgsConstructor
public class SemanticQueryPlanner {

    private final SemanticRegistry semanticRegistry;

    /** 查询计划：capability id + 业务参数（交给 CapabilityService 走完整管线） */
    public record QueryPlan(MetricDefinition metric, String capabilityId,
                            List<String> dimensions, Map<String, Object> params) {}

    /**
     * 把语义查询翻译为查询计划。
     * 维度校验：请求维度必须是指标声明的支持维度子集。
     */
    public QueryPlan plan(SemanticQuery query) {
        if (query == null || query.metric() == null || query.metric().isBlank()) {
            throw ApiException.paramInvalid("语义查询缺少 metric");
        }
        MetricDefinition metric = semanticRegistry.requireMetric(query.metric());

        // 维度校验：未声明的维度直接拒绝（防模型乱拼维度）
        List<String> dims = query.dimensions() == null ? List.of() : query.dimensions();
        for (String dim : dims) {
            if (!metric.getDimensions().contains(dim)) {
                throw ApiException.paramInvalid(
                        "指标 " + metric.getId() + " 不支持维度 " + dim
                        + "，可用维度: " + metric.getDimensions());
            }
        }

        // 参数映射：语义过滤条件 -> capability 业务参数（权限参数仍由管线注入）
        Map<String, Object> params = new HashMap<>();
        if (query.filters() != null) {
            query.filters().forEach((k, v) -> {
                if (v != null) {
                    params.put(k, v);
                }
            });
        }
        if (query.timeRange() != null && !query.timeRange().isBlank()) {
            params.put("time_range", query.timeRange());
        }
        return new QueryPlan(metric, metric.getCapability(), dims, params);
    }
}
