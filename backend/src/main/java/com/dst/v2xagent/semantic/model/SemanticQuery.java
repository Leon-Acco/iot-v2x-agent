package com.dst.v2xagent.semantic.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * 语义查询模型：LLM / 前端只产出本结构，永不直接产出 SQL
 * 由 Query Planner 确定性映射为 capability 调用
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SemanticQuery(
        /** 指标 id（semantic/metrics.yaml 注册） */
        String metric,
        /** 语义维度列表（可空） */
        List<String> dimensions,
        /** 过滤条件：vehicle / vin_list / fleet_id 等业务参数 */
        Map<String, Object> filters,
        /** 时间范围表达式（如 近 7 天） */
        String timeRange
) {}
