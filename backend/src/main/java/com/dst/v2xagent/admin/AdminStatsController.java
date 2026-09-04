package com.dst.v2xagent.admin;

import com.dst.v2xagent.agui.auth.AuthFilter;
import com.dst.v2xagent.common.PermissionContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 效果看板（P1）：运行量/成功率/拒答率/延迟/反馈比/高频能力。
 * 数据来源：run_audit + feedback（只读管理视角，不止模型自评）。
 */
@Slf4j
@RestController
@RequestMapping("/admin/stats")
@RequiredArgsConstructor
public class AdminStatsController {

    private final JdbcTemplate controlJdbcTemplate;
    private final com.dst.v2xagent.query.SlowQueryRecorder slowQueryRecorder;
    private final com.dst.v2xagent.observability.trace.TraceRecorder traceRecorder;

    @GetMapping("/overview")
    public Map<String, Object> overview(HttpServletRequest request) {
        PermissionContext ctx = (PermissionContext) request.getAttribute(AuthFilter.ATTR_PERMISSION);
        if (ctx == null || !ctx.isAdmin()) {
            throw com.dst.v2xagent.common.ApiException.scopeDenied("仅管理员可用");
        }
        Map<String, Object> out = new LinkedHashMap<>();

        Map<String, Object> runs = controlJdbcTemplate.queryForMap(
                "SELECT COUNT(1) AS total,"
                + " SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END) AS success,"
                + " SUM(CASE WHEN status = 'REFUSED' THEN 1 ELSE 0 END) AS refused,"
                + " SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS failed,"
                + " SUM(CASE WHEN status = 'CANCELLED' THEN 1 ELSE 0 END) AS cancelled,"
                + " ROUND(AVG(elapsed_ms), 0) AS avg_elapsed_ms"
                + " FROM run_audit WHERE tenant_id = 'T1'");
        out.put("runs", runs);

        Map<String, Object> today = controlJdbcTemplate.queryForMap(
                "SELECT COUNT(1) AS total,"
                + " SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END) AS success"
                + " FROM run_audit WHERE tenant_id = 'T1' AND created_at >= CURDATE()");
        out.put("today", today);

        Map<String, Object> feedback = controlJdbcTemplate.queryForMap(
                "SELECT COUNT(1) AS total,"
                + " SUM(CASE WHEN rating > 0 THEN 1 ELSE 0 END) AS up,"
                + " SUM(CASE WHEN rating < 0 THEN 1 ELSE 0 END) AS down"
                + " FROM feedback WHERE tenant_id = 'T1'");
        out.put("feedback", feedback);

        List<Map<String, Object>> topCaps = controlJdbcTemplate.queryForList(
                "SELECT capability_id, COUNT(1) AS cnt,"
                + " ROUND(AVG(elapsed_ms), 0) AS avg_ms"
                + " FROM run_audit WHERE tenant_id = 'T1' AND capability_id IS NOT NULL"
                + " GROUP BY capability_id ORDER BY cnt DESC LIMIT 5");
        out.put("topCapabilities", topCaps);

        List<Map<String, Object>> errors = controlJdbcTemplate.queryForList(
                "SELECT error_code, COUNT(1) AS cnt FROM run_audit"
                + " WHERE tenant_id = 'T1' AND error_code IS NOT NULL"
                + " GROUP BY error_code ORDER BY cnt DESC LIMIT 5");
        out.put("topErrors", errors);

        List<Map<String, Object>> uncovered = controlJdbcTemplate.queryForList(
                "SELECT question, COUNT(1) AS cnt FROM run_audit"
                + " WHERE tenant_id = 'T1' AND status = 'REFUSED' AND failed_stage = 'understand'"
                + " GROUP BY question ORDER BY cnt DESC LIMIT 10");
        out.put("uncoveredQuestions", uncovered);

        return out;
    }

    /** 慢查询监控：内存环形缓冲（实时）+ 落库历史（近 50 条） */
    @org.springframework.web.bind.annotation.GetMapping("/slow-queries")
    public Map<String, Object> slowQueries(HttpServletRequest request) {
        PermissionContext ctx = (PermissionContext) request.getAttribute(AuthFilter.ATTR_PERMISSION);
        if (ctx == null || !ctx.isAdmin()) {
            throw com.dst.v2xagent.common.ApiException.scopeDenied("仅管理员可用");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("recent", slowQueryRecorder.recent());
        try {
            out.put("history", controlJdbcTemplate.queryForList(
                    "SELECT trace_id, capability_id, sql_fingerprint, duration_ms, row_count, username, created_at"
                    + " FROM query_slow_log ORDER BY id DESC LIMIT 50"));
        } catch (Exception e) {
            out.put("history", List.of());
        }
        return out;
    }

    /**
     * Agent 评估指标（借鉴 Office_Agent L1 免费层：日志推导、零 token 成本）。
     * 数据源：run_audit（run 维度）+ feedback（用户反馈）。
     * 口径与动作映射：
     * - 失败率/拒答率高 → 看错误码分布定位链路；拒答集中在 understand = 能力覆盖不足
     * - 平均耗时高 → 慢查询榜联动定位
     * - 路由分布（copilot:xxx）→ cross 占比高说明路由常兜底（超时/额度问题），专家命中率低
     * - 点赞率 → 唯一的用户满意度信号
     */
    @GetMapping("/agent-eval")
    public Map<String, Object> agentEval(HttpServletRequest request,
                                         @org.springframework.web.bind.annotation.RequestParam(
                                                 defaultValue = "7") int days) {
        PermissionContext ctx = (PermissionContext) request.getAttribute(AuthFilter.ATTR_PERMISSION);
        if (ctx == null || !ctx.isAdmin()) {
            throw com.dst.v2xagent.common.ApiException.scopeDenied("仅管理员可用");
        }
        if (days < 1 || days > 90) {
            days = 7;
        }
        String window = "FROM run_audit WHERE tenant_id = 'T1' AND created_at >= DATE_SUB(NOW(), INTERVAL " + days + " DAY)";

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", days);

        // ── 全局概况 ──
        Map<String, Object> ov = controlJdbcTemplate.queryForMap(
                "SELECT COUNT(1) AS total,"
                + " SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END) AS success,"
                + " SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS failed,"
                + " SUM(CASE WHEN status = 'REFUSED' THEN 1 ELSE 0 END) AS refused,"
                + " SUM(CASE WHEN status = 'CANCELLED' THEN 1 ELSE 0 END) AS cancelled,"
                + " ROUND(AVG(elapsed_ms), 0) AS avg_ms,"
                + " ROUND(AVG(row_count), 1) AS avg_rows"
                + " " + window);
        // 拒答集中在 understand 阶段的比例（= 能力覆盖不足的信号）
        Map<String, Object> uncovered = controlJdbcTemplate.queryForMap(
                "SELECT COALESCE(SUM(CASE WHEN failed_stage = 'understand' THEN 1 ELSE 0 END), 0) AS understand_cnt"
                + " " + window + " AND status = 'REFUSED'");
        ov.put("refused_understand", uncovered.get("understand_cnt"));
        Map<String, Object> fb = controlJdbcTemplate.queryForMap(
                "SELECT COUNT(1) AS total, SUM(CASE WHEN rating > 0 THEN 1 ELSE 0 END) AS up"
                + " FROM feedback WHERE tenant_id = 'T1' AND created_at >= DATE_SUB(NOW(), INTERVAL " + days + " DAY)");
        ov.put("feedback_total", fb.get("total"));
        ov.put("feedback_up", fb.get("up"));
        out.put("overview", ov);

        // ── 路由分布（copilot:xxx 为 fleet_copilot 路由标签）──
        List<Map<String, Object>> byRoute = controlJdbcTemplate.queryForList(
                "SELECT capability_id AS route, COUNT(1) AS runs,"
                + " SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END) AS ok,"
                + " ROUND(AVG(elapsed_ms), 0) AS avg_ms"
                + " " + window + " AND capability_id LIKE 'copilot:%'"
                + " GROUP BY capability_id ORDER BY runs DESC");
        out.put("byRoute", byRoute);

        // ── 能力分桶（capability 链路，非 copilot 前缀）──
        List<Map<String, Object>> byCapability = controlJdbcTemplate.queryForList(
                "SELECT capability_id, COUNT(1) AS runs,"
                + " SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END) AS ok,"
                + " SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS failed,"
                + " ROUND(AVG(elapsed_ms), 0) AS avg_ms,"
                + " ROUND(AVG(row_count), 1) AS avg_rows"
                + " " + window + " AND capability_id IS NOT NULL AND capability_id NOT LIKE 'copilot:%'"
                + " GROUP BY capability_id ORDER BY runs DESC LIMIT 15");
        out.put("byCapability", byCapability);

        // ── 错误码分布 ──
        List<Map<String, Object>> errors = controlJdbcTemplate.queryForList(
                "SELECT error_code, COUNT(1) AS cnt" + " " + window
                + " AND error_code IS NOT NULL GROUP BY error_code ORDER BY cnt DESC LIMIT 8");
        out.put("topErrors", errors);

        // ── 按天趋势 ──
        List<Map<String, Object>> daily = controlJdbcTemplate.queryForList(
                "SELECT DATE(created_at) AS day, COUNT(1) AS runs,"
                + " SUM(CASE WHEN status = 'SUCCESS' THEN 1 ELSE 0 END) AS ok,"
                + " SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS failed,"
                + " SUM(CASE WHEN status = 'REFUSED' THEN 1 ELSE 0 END) AS refused,"
                + " ROUND(AVG(elapsed_ms), 0) AS avg_ms"
                + " " + window + " GROUP BY DATE(created_at) ORDER BY day");
        out.put("daily", daily);
        return out;
    }

    /** Agent Trace：最近 traceId 列表 */
    @org.springframework.web.bind.annotation.GetMapping("/traces")
    public Object traces(HttpServletRequest request) {
        PermissionContext ctx = (PermissionContext) request.getAttribute(AuthFilter.ATTR_PERMISSION);
        if (ctx == null || !ctx.isAdmin()) {
            throw com.dst.v2xagent.common.ApiException.scopeDenied("仅管理员可用");
        }
        return traceRecorder.recentTraceIds(50);
    }

    /** Agent Trace：按 traceId 回查 span 明细与摘要 */
    @org.springframework.web.bind.annotation.GetMapping("/traces/{traceId}")
    public Map<String, Object> traceDetail(HttpServletRequest request,
                                           @org.springframework.web.bind.annotation.PathVariable String traceId) {
        PermissionContext ctx = (PermissionContext) request.getAttribute(AuthFilter.ATTR_PERMISSION);
        if (ctx == null || !ctx.isAdmin()) {
            throw com.dst.v2xagent.common.ApiException.scopeDenied("仅管理员可用");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", traceRecorder.summarize(traceId));
        out.put("spans", traceRecorder.byTraceId(traceId));
        return out;
    }
}
