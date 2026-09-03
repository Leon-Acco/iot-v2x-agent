package com.dst.v2xagent.analysis.model;

import java.util.List;
import java.util.Map;

/** 分析报告：证据集 + 相关性 + 元信息（LLM 只负责解释证据，不制造证据） */
public record AnalysisReport(
        /** 分析主体（车队 / 车辆） */
        String subject,
        /** 当期时间范围 */
        String timeRange,
        /** 基线时间范围 */
        String baselineRange,
        /** 证据列表 */
        List<Evidence> evidences,
        /** 相关性结果：metricA x metricB -> pearson */
        List<Map<String, Object>> correlations,
        /** 异常数量 */
        long anomalyCount
) {}
