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
}
