package com.dst.v2xagent.query;

/**
 * 查询预算：所有分析查询的资源上限（超时 / 行数 / 扫描量 / 时间跨度）
 * 超限由防火墙拒绝或执行器截断，绝不静默放行
 */
public record QueryBudget(int timeoutMs, int maxRows, long maxScanRows, int maxTimeRangeDays) {

    /** 默认预算：15s / 1000 行 / 1000 万扫描行 / 90 天 */
    public static QueryBudget defaults() {
        return new QueryBudget(15000, 1000, 10_000_000L, 90);
    }
}
