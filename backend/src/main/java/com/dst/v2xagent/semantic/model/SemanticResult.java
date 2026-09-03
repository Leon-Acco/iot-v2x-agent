package com.dst.v2xagent.semantic.model;

import com.dst.v2xagent.capability.model.TableResult;

import java.util.List;
import java.util.Map;

/**
 * 语义查询结果：指标值 + 维度行 + 证据链（Answer Grounding 的载体）
 * 每个数字都能追溯到 capability / 查询指纹 / 时效快照
 */
public record SemanticResult(
        /** 指标 id */
        String metric,
        /** 指标中文名 */
        String metricName,
        /** 单位 */
        String unit,
        /** 请求维度 */
        List<String> dimensions,
        /** 标量值（无维度查询时有值） */
        Double value,
        /** 维度行：每行含维度值与 value */
        List<Map<String, Object>> rows,
        /** 证据链信息 */
        Evidence evidence
) {
    /** 证据链：答案可追溯到查询与数据快照 */
    public record Evidence(
            String capabilityId,
            int capabilityVersion,
            String queryFingerprint,
            long elapsedMs,
            boolean truncated,
            TableResult.Freshness freshness
    ) {}
}
