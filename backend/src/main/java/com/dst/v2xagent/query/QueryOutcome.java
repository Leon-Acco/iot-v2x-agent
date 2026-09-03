package com.dst.v2xagent.query;

import java.util.List;

/** 统一查询执行产物：行数据 + 耗时 + 截断标记（截断绝不静默） */
public record QueryOutcome(List<Object[]> rows, int columnCount, long elapsedMs, boolean truncated) {}
