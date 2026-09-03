package com.dst.v2xagent.query;

import com.dst.v2xagent.common.PermissionContext;
import com.dst.v2xagent.observability.trace.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 慢查询记录器：超过阈值的查询落库（query_slow_log）+ 内存环形缓冲（管理后台实时查看）
 * SQL 只存 sha256 指纹，不落明文（防敏感参数泄漏）
 */
@Slf4j
@Component
public class SlowQueryRecorder {

    private final JdbcTemplate controlJdbcTemplate;
    private final long thresholdMs;
    /** 内存环形缓冲：最近 100 条慢查询 */
    private final Deque<Map<String, Object>> recent = new ConcurrentLinkedDeque<>();

    public SlowQueryRecorder(@Qualifier("controlJdbcTemplate") JdbcTemplate controlJdbcTemplate,
                             @Value("${query.slow-threshold-ms:3000}") long thresholdMs) {
        this.controlJdbcTemplate = controlJdbcTemplate;
        this.thresholdMs = thresholdMs;
    }

    /** 超过阈值则记录：内存缓冲 + 落库（落库失败只告警不影响主流程） */
    public void recordIfSlow(String source, String sql, long durationMs, int rows, int maxRows, PermissionContext ctx) {
        if (durationMs < thresholdMs) {
            return;
        }
        String traceId = TraceContext.currentTraceId();
        String fingerprint = fingerprint(sql);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("trace_id", traceId);
        entry.put("capability_id", source);
        entry.put("sql_fingerprint", fingerprint);
        entry.put("duration_ms", durationMs);
        entry.put("row_count", rows);
        entry.put("max_rows", maxRows);
        entry.put("username", ctx != null ? ctx.username() : null);
        recent.addFirst(entry);
        while (recent.size() > 100) {
            recent.pollLast();
        }
        log.warn("slow query: source={} duration={}ms rows={} trace={}", source, durationMs, rows, traceId);
        try {
            controlJdbcTemplate.update(
                    "INSERT INTO query_slow_log (trace_id, capability_id, sql_fingerprint, duration_ms, row_count, max_rows, tenant_id, username)"
                    + " VALUES (?,?,?,?,?,?,?,?)",
                    traceId, source, fingerprint, durationMs, rows, maxRows,
                    ctx != null ? ctx.tenantId() : "T1", ctx != null ? ctx.username() : null);
        } catch (Exception e) {
            log.warn("慢查询落库失败: {}", e.getMessage());
        }
    }

    /** 最近慢查询（管理后台用） */
    public List<Map<String, Object>> recent() {
        return List.copyOf(recent);
    }

    /** SQL 指纹：归一化空白与字量后 sha256，去掉参数差异聚合同类慢查询 */
    private String fingerprint(String sql) {
        try {
            String normalized = sql.replaceAll("\\s+", " ").replaceAll("'[^']*'", "?").trim();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
