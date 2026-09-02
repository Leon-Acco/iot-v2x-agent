package com.dst.v2xagent.admin;

import com.dst.v2xagent.agui.auth.AuthFilter;
import com.dst.v2xagent.capability.CapabilityRegistry;
import com.dst.v2xagent.capability.CapabilityService;
import com.dst.v2xagent.capability.model.CapabilityDefinition;
import com.dst.v2xagent.common.PermissionContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * A1 Capability 管理后台 API + A4 反馈列表（雏形）
 * 全部操作要求 admin 角色。
 */
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final CapabilityRegistry registry;
    private final CapabilityService capabilityService;
    private final JdbcTemplate controlJdbcTemplate;

    /** capability 列表 */
    @GetMapping("/capabilities")
    public List<CapabilityDefinition> list(HttpServletRequest request) {
        requireAdmin(request);
        return registry.listAll();
    }

    /** 新增 capability（草稿，需过静态校验） */
    @PostMapping("/capabilities")
    public Map<String, Object> create(@RequestBody CapabilityDefinition def, HttpServletRequest request) {
        requireAdmin(request);
        registry.create(def);
        return Map.of("success", true);
    }

    /** 更新 capability（自动递增版本，缓存自动失效） */
    @PutMapping("/capabilities/{id}")
    public Map<String, Object> update(@PathVariable String id, @RequestBody CapabilityDefinition def,
                                      HttpServletRequest request) {
        requireAdmin(request);
        def.setId(id);
        def.setVersion(registry.require(id).getVersion());
        registry.update(def);
        return Map.of("success", true);
    }

    /** 上下线：online / deprecated / draft / staging */
    public record StatusRequest(String status) {}

    @PostMapping("/capabilities/{id}/status")
    public Map<String, Object> changeStatus(@PathVariable String id, @RequestBody StatusRequest req,
                                            HttpServletRequest request) {
        requireAdmin(request);
        if (!List.of("draft", "staging", "online", "deprecated").contains(req.status())) {
            throw new IllegalArgumentException("非法状态: " + req.status());
        }
        registry.changeStatus(id, req.status());
        return Map.of("success", true);
    }

    /** 试跑（dry-run）：走完整管线与护栏，返回真实 SQL、行数、耗时 */
    public record DryRunRequest(Map<String, Object> params) {}

    @PostMapping("/capabilities/{id}/dry-run")
    public Map<String, Object> dryRun(@PathVariable String id, @RequestBody DryRunRequest req,
                                      HttpServletRequest request) {
        PermissionContext ctx = requireAdmin(request);
        CapabilityService.InvokeOutcome outcome = capabilityService.dryRun(id,
                req.params() != null ? req.params() : Map.of(), ctx);
        return Map.of(
                "sqlSnapshot", outcome.table().stats().sqlSnapshot(),
                "rowCount", outcome.table().rowCount(),
                "truncated", outcome.table().truncated(),
                "elapsedMs", outcome.table().stats().elapsedMs(),
                "columns", outcome.table().columns(),
                "rows", outcome.table().rows().stream().limit(20).map(r -> java.util.Arrays.stream(r)
                        .map(c -> c == null ? null : c.toString()).toList()).toList());
    }

    /** 反馈列表（A4 雏形：👍👎 + traceId + 问题） */
    @GetMapping("/feedback")
    public List<Map<String, Object>> feedback(HttpServletRequest request) {
        requireAdmin(request);
        return controlJdbcTemplate.queryForList("""
                SELECT f.run_id, f.trace_id, f.rating, f.comment, f.created_at,
                       a.question, a.capability_id, a.status AS run_status
                FROM feedback f LEFT JOIN run_audit a ON a.run_id = f.run_id AND a.tenant_id = f.tenant_id
                WHERE f.tenant_id = 'T1'
                ORDER BY f.id DESC LIMIT 200
                """);
    }

    /** 运行审计列表（A4 雏形） */
    @GetMapping("/audit/runs")
    public List<Map<String, Object>> auditRuns(HttpServletRequest request) {
        requireAdmin(request);
        return controlJdbcTemplate.queryForList("""
                SELECT run_id, trace_id, user_id, profile_id, question, capability_id, status,
                       error_code, elapsed_ms, row_count, created_at
                FROM run_audit WHERE tenant_id = 'T1'
                ORDER BY id DESC LIMIT 200
                """);
    }

    private PermissionContext requireAdmin(HttpServletRequest request) {
        PermissionContext ctx = (PermissionContext) request.getAttribute(AuthFilter.ATTR_PERMISSION);
        if (!ctx.isAdmin()) {
            throw com.dst.v2xagent.common.ApiException.scopeDenied("需要管理员角色");
        }
        return ctx;
    }
}
