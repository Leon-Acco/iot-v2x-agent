package com.dst.v2xagent.analysis;

import java.util.List;

/**
 * 基线分析器（纯函数，可单测）
 * 职责：序列基线计算、变化百分比、异常判定。
 * 绝不造数：分母为 0 / 数据不足时返回 null。
 */
public final class BaselineAnalyzer {

    private BaselineAnalyzer() {}

    /** 默认异常阈值：|变化| 超过 30% */
    public static final double DEFAULT_THRESHOLD_PCT = 30.0;

    /** 均值：空集返回 null */
    public static Double mean(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        double sum = 0;
        int n = 0;
        for (Double v : values) {
            if (v != null) {
                sum += v;
                n++;
            }
        }
        return n == 0 ? null : sum / n;
    }

    /** 变化百分比：(current - baseline) / baseline * 100；基线为 0/null 返回 null */
    public static Double pctChange(Double current, Double baseline) {
        if (current == null || baseline == null || baseline == 0) {
            return null;
        }
        return (current - baseline) / baseline * 100.0;
    }

    /** 异常判定：|变化百分比| 超过阈值 */
    public static boolean isAnomaly(Double pctChange, double thresholdPct) {
        return pctChange != null && Math.abs(pctChange) > thresholdPct;
    }

    /** 标准差（总体）：数据不足返回 null */
    public static Double stddev(List<Double> values) {
        Double mean = mean(values);
        if (mean == null) {
            return null;
        }
        double sum = 0;
        int n = 0;
        for (Double v : values) {
            if (v != null) {
                sum += (v - mean) * (v - mean);
                n++;
            }
        }
        return n == 0 ? null : Math.sqrt(sum / n);
    }

    /** z-score 异常：|z| > 2 且样本 ≥ 3 */
    public static boolean isAnomalyByZScore(double current, List<Double> baselineValues) {
        if (baselineValues == null || baselineValues.size() < 3) {
            return false;
        }
        Double mean = mean(baselineValues);
        Double std = stddev(baselineValues);
        if (mean == null || std == null || std == 0) {
            return false;
        }
        return Math.abs((current - mean) / std) > 2.0;
    }
}
