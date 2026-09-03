package com.dst.v2xagent.analysis.model;

/** 证据：单个指标的当期 vs 基线对比结果（Answer Grounding 载体） */
public record Evidence(
        /** 指标 id */
        String metric,
        /** 指标中文名 */
        String metricName,
        /** 单位 */
        String unit,
        /** 当期值 */
        Double currentValue,
        /** 基线值（上一周期） */
        Double baselineValue,
        /** 变化百分比（%，分母为 0 时为 null，不造数） */
        Double deltaPct,
        /** 是否异常（超阈值） */
        boolean anomaly,
        /** 证据链：capability + 查询指纹 */
        String evidenceRef
) {}
