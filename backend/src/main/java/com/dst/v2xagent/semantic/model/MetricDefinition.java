package com.dst.v2xagent.semantic.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 指标定义：语义层核心资产
 * 绑定 capability 契约（语义层不直接写 SQL），声明取值列、维度列映射与可选比率公式
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MetricDefinition {
    /** 指标 id，如 vehicle.alarm_count */
    private String id;
    /** 中文名（给人看） */
    private String name;
    /** 描述（给模型看） */
    private String description;
    /** 单位：count / km / percent 等 */
    private String unit = "count";
    /** 绑定的 capability id */
    private String capability;
    /** 取值列（capability 返回中的 metric 列名） */
    private String valueColumn;
    /** 支持的语义维度 */
    private List<String> dimensions = List.of();
    /** 语义维度 -> capability 返回列名 */
    private Map<String, String> dimensionColumns = Map.of();
    /** 无维度查询时的标量聚合方式：sum / count */
    private String aggregate = "sum";
    /** 比率公式（同结果两列相除，如在线率） */
    private Formula formula;

    /** 比率公式：value = numerator / denominator（同一结果集的两列） */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Formula {
        private String numerator;
        private String denominator;
    }
}
