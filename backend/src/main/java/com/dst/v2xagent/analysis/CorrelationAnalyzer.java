package com.dst.v2xagent.analysis;

import java.util.List;

/**
 * 相关性分析器（纯函数，可单测）
 * Pearson 相关系数：归一化到 [-1, 1]，样本 < 3 或方差为 0 返回 null。
 */
public final class CorrelationAnalyzer {

    private CorrelationAnalyzer() {}

    /** Pearson 相关系数：两等长序列，样本不足或零方差返回 null */
    public static Double pearson(List<Double> x, List<Double> y) {
        if (x == null || y == null || x.size() != y.size() || x.size() < 3) {
            return null;
        }
        int n = x.size();
        Double meanX = BaselineAnalyzer.mean(x);
        Double meanY = BaselineAnalyzer.mean(y);
        if (meanX == null || meanY == null) {
            return null;
        }
        double num = 0, denX = 0, denY = 0;
        for (int i = 0; i < n; i++) {
            Double xi = x.get(i);
            Double yi = y.get(i);
            if (xi == null || yi == null) {
                return null;
            }
            double dx = xi - meanX;
            double dy = yi - meanY;
            num += dx * dy;
            denX += dx * dx;
            denY += dy * dy;
        }
        if (denX == 0 || denY == 0) {
            return null;
        }
        return num / Math.sqrt(denX * denY);
    }

    /** 相关强度描述：|r| 分档 */
    public static String strength(Double r) {
        if (r == null) {
            return "unknown";
        }
        double abs = Math.abs(r);
        if (abs >= 0.8) {
            return "strong";
        }
        if (abs >= 0.5) {
            return "moderate";
        }
        if (abs >= 0.3) {
            return "weak";
        }
        return "none";
    }
}
