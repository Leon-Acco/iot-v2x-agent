package com.dst.v2xagent.capability.model;

import java.util.List;

/**
 * capability 执行结果（表格为唯一载体，图表由 ChartSpec 引用同一份数据）
 */
public record TableResult(
        List<CapabilityDefinition.ColumnDef> columns,
        List<Object[]> rows,
        int rowCount,
        /** 是否被 max_rows 截断：截断必须对前端可见，绝不静默截断 */
        boolean truncated,
        Freshness freshness,
        ExecStats stats
) {
    /** 数据时效：取自真实执行时刻，前端展示「数据截至 xx:xx」 */
    public record Freshness(String dataAsOf, String policyType, int expectedDelayMin, String warning) {}

    /** 执行统计：耗时、扫描行数、脱敏 SQL 快照（执行过程区展示） */
    public record ExecStats(long elapsedMs, int scannedRows, String sqlSnapshot) {}
}
