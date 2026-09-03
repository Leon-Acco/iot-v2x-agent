package com.dst.v2xagent.visualization;

import java.util.List;
import java.util.Map;

/**
 * 标准图表契约（ChartSpec）：前端唯一渲染依据
 * 数据引用优先（data.source = result_id），内联行为立即渲染副本。
 */
public record ChartSpec(
        /** 图表类型：kpi / bar / line / pie / map / table */
        String type,
        /** 标题 */
        String title,
        /** 数据：source=result_id，rows=内联数据 */
        DataBlock data,
        /** 编码：x / y / series */
        Map<String, String> encoding
) {
    /** 数据块：引用 + 内联副本 */
    public record DataBlock(String source, List<String> columns, List<List<Object>> rows) {}
}
