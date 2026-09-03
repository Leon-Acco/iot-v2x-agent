package com.dst.v2xagent.observability.trace;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 追踪落库器：请求结束时把 TraceContext 中的 span 批量写入 agent_trace 表
 * 落库失败只告警不影响主流程；提供按 traceId 回查能力（管理后台 / AG-UI Trace）
 */
@Slf4j
@Component
public class TraceRecorder {

    private final JdbcTemplate controlJdbcTemplate;

    /** 显式注入控制库 JdbcTemplate（双数据源强隔离） */
    public TraceRecorder(@Qualifier("controlJdbcTemplate") JdbcTemplate controlJdbcTemplate) {
        this.controlJdbcTemplate = controlJdbcTemplate;
    }

    /** 批量落库（空列表直接跳过） */
    public void flush(String traceId, List<TraceSpan> spans) {
        if (traceId == null || spans == null || spans.isEmpty()) {
            return;
        }
        try {
            controlJdbcTemplate.batchUpdate(
                    "INSERT INTO agent_trace (trace_id, span_type, name, start_ms, duration_ms, status, detail)"
                    + " VALUES (?,?,?,?,?,?,?)",
                    spans.stream().map(s -> new Object[]{
                            traceId, s.spanType(), s.name(), s.startMs(), s.durationMs(), s.status(), s.detail()
                    }).toList());
        } catch (Exception e) {
            log.warn("追踪落库失败: {}", e.getMessage());
        }
    }

    /** 按 traceId 回查全部 span（时间升序） */
    public List<Map<String, Object>> byTraceId(String traceId) {
        try {
            return controlJdbcTemplate.queryForList(
                    "SELECT trace_id, span_type, name, start_ms, duration_ms, status, detail, created_at"
                    + " FROM agent_trace WHERE trace_id = ? ORDER BY start_ms",
                    traceId);
        } catch (Exception e) {
            log.warn("追踪回查失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 最近 traceId 列表（管理后台） */
    public List<Map<String, Object>> recentTraceIds(int limit) {
        try {
            return controlJdbcTemplate.queryForList(
                    "SELECT trace_id, COUNT(1) AS spans, SUM(duration_ms) AS total_ms, MAX(created_at) AS last_at"
                    + " FROM agent_trace GROUP BY trace_id ORDER BY last_at DESC LIMIT ?",
                    Math.max(1, Math.min(limit, 200)));
        } catch (Exception e) {
            log.warn("追踪列表失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 运行期指标摘要（span 类型分布） */
    public Map<String, Object> summarize(String traceId) {
        List<Map<String, Object>> spans = byTraceId(traceId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("trace_id", traceId);
        out.put("span_count", spans.size());
        out.put("query_count", spans.stream().filter(s -> "capability".equals(s.get("span_type")) || "sql".equals(s.get("span_type"))).count());
        out.put("memory_recall_count", spans.stream().filter(s -> "memory".equals(s.get("span_type"))).count());
        out.put("total_duration_ms", spans.stream().mapToLong(s -> ((Number) s.get("duration_ms")).longValue()).sum());
        return out;
    }
}
