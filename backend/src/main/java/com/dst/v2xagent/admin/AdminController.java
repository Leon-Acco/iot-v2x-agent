package com.dst.v2xagent.admin;

import com.dst.v2xagent.agui.auth.AuthFilter;
import com.dst.v2xagent.capability.CapabilityAiGenerator;
import com.dst.v2xagent.capability.CapabilityLint;
import com.dst.v2xagent.capability.CapabilityRegistry;
import com.dst.v2xagent.capability.CapabilityService;
import com.dst.v2xagent.capability.DryRunSampleService;
import com.dst.v2xagent.capability.SchemaIntrospectionService;
import com.dst.v2xagent.capability.model.CapabilityDefinition;
import com.dst.v2xagent.common.ApiException;
import com.dst.v2xagent.common.PermissionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Capability 管理后台 API：CRUD + 上下线 + 试跑 + AI 生成 + 审批流 + 版本历史 + 反馈/审计
 * 全部操作要求 admin 角色。
 * 审批规则：新建（AI/手动）自动建立待审批单；上线前必须存在已通过的审批单（镜像 memory_proposal 模式）。
 */
@Slf4j
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final CapabilityRegistry registry;
    private final CapabilityService capabilityService;
    private final SchemaIntrospectionService schemaService;
    private final CapabilityAiGenerator aiGenerator;
    private final DryRunSampleService dryRunSampleService;
    private final CapabilityLint capabilityLint;
    private final JdbcTemplate controlJdbcTemplate;

    /** 审批单 payload 序列化（camelCase，与前端回显字段一致） */
    private final ObjectMapper camelMapper = new ObjectMapper();

    /** capability 列表 */
    @GetMapping("/capabilities")
    public List<CapabilityDefinition> list(HttpServletRequest request) {
        requireAdmin(request);
        return registry.listAll();
    }

    /** 能力元数据质量检查（完整度评分 + 问题清单，前端合并展示） */
    @GetMapping("/capabilities-lint")
    public List<CapabilityLint.LintResult> lint(HttpServletRequest request) {
        requireAdmin(request);
        return capabilityLint.lintAll(registry.listAll());
    }

    /** 新增 capability（草稿，需过静态校验）；同步自动建立待审批单 */
    @PostMapping("/capabilities")
    public Map<String, Object> create(@RequestBody CapabilityDefinition def, HttpServletRequest request) {
        PermissionContext ctx = requireAdmin(request);
        registry.create(def);
        insertProposal(def.getId(), "create", def, ctx.username());
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

    /** 上下线：online / deprecated / draft / staging；上线需先通过审批 */
    public record StatusRequest(String status) {}

    @PostMapping("/capabilities/{id}/status")
    public Map<String, Object> changeStatus(@PathVariable String id, @RequestBody StatusRequest req,
                                            HttpServletRequest request) {
        PermissionContext ctx = requireAdmin(request);
        if (!List.of("draft", "staging", "online", "deprecated").contains(req.status())) {
            throw new IllegalArgumentException("非法状态: " + req.status());
        }
        if ("online".equals(req.status()) && !hasApprovedProposal(id)) {
            CapabilityDefinition def = registry.require(id);
            insertProposal(id, "online", def, ctx.username());
            return Map.of("success", false, "needApproval", true,
                    "message", "已提交审批，通过后方可上线");
        }
        registry.changeStatus(id, req.status());
        return Map.of("success", true);
    }

    /** 试跑（dry-run）：走完整管线与护栏（跳过状态校验），返回真实 SQL、行数、耗时 */
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

    /** 试跑样例参数：daterange→近N天、车辆→权限内真实车牌/VIN（让 dry-run 开箱有数据） */
    @GetMapping("/capabilities/{id}/sample-params")
    public Map<String, Object> sampleParams(@PathVariable String id, HttpServletRequest request) {
        PermissionContext ctx = requireAdmin(request);
        return dryRunSampleService.sampleParams(registry.require(id), ctx);
    }

    // ==================== 表结构自省（AI 生成选表用） ====================

    /** 分析库全部表（表名 + 注释） */
    @GetMapping("/schema/tables")
    public List<Map<String, Object>> schemaTables(HttpServletRequest request) {
        requireAdmin(request);
        return schemaService.listTables();
    }

    /** 指定表的列信息 */
    @GetMapping("/schema/tables/{table}/columns")
    public List<Map<String, Object>> schemaColumns(@PathVariable String table, HttpServletRequest request) {
        requireAdmin(request);
        return schemaService.listColumns(table);
    }

    // ==================== AI 生成能力 ====================

    /** AI 生成请求：用户描述（可空，空则自动分析模式）+ 选定数据表 */
    public record AiGenRequest(String description, List<String> tables) {}

    /** AI 生成能力草稿（不落库；确认后走 POST /capabilities 保存并自动建审批单） */
    @PostMapping("/capabilities/ai-generate")
    public Map<String, Object> aiGenerate(@RequestBody AiGenRequest req, HttpServletRequest request) {
        requireAdmin(request);
        CapabilityAiGenerator.GenResult result = aiGenerator.generate(req.description(), req.tables());
        return Map.of(
                "definition", result.definition(),
                "mock", result.mock(),
                "notice", result.notice() == null ? "" : result.notice());
    }

    // ==================== 审批中心 ====================

    /** 审批单列表（可按状态过滤，payload 解析为对象方便前端展示） */
    @GetMapping("/capability-proposals")
    public List<Map<String, Object>> proposals(@RequestParam(required = false) String status,
                                               HttpServletRequest request) {
        requireAdmin(request);
        boolean filter = status != null && !status.isBlank();
        String sql = "SELECT * FROM capability_proposal WHERE tenant_id = 'T1'"
                + (filter ? " AND status = ?" : "") + " ORDER BY id DESC LIMIT 100";
        List<Map<String, Object>> rows = filter
                ? controlJdbcTemplate.queryForList(sql, status)
                : controlJdbcTemplate.queryForList(sql);
        for (Map<String, Object> r : rows) {
            Object payload = r.get("payload");
            if (payload != null) {
                try {
                    r.put("payload", camelMapper.readValue(String.valueOf(payload), Map.class));
                } catch (Exception ignore) {
                    // payload 保持原文，不影响列表
                }
            }
        }
        return rows;
    }

    /** 审批通过：能力置为 online */
    @PostMapping("/capability-proposals/{pid}/approve")
    public Map<String, Object> approveProposal(@PathVariable long pid, HttpServletRequest request) {
        PermissionContext ctx = requireAdmin(request);
        List<Map<String, Object>> rows = controlJdbcTemplate.queryForList(
                "SELECT * FROM capability_proposal WHERE tenant_id = 'T1' AND id = ? AND status = 'PENDING'", pid);
        if (rows.isEmpty()) {
            return Map.of("success", false, "message", "审批单不存在或已处理");
        }
        String capId = String.valueOf(rows.get(0).get("capability_id"));
        registry.changeStatus(capId, "online");
        controlJdbcTemplate.update(
                "UPDATE capability_proposal SET status = 'APPROVED', reviewed_by = ?,"
                + " reviewed_at = CURRENT_TIMESTAMP(3) WHERE tenant_id = 'T1' AND id = ?",
                ctx.username(), pid);
        log.info("capability 审批上线: {} by {}", capId, ctx.username());
        return Map.of("success", true);
    }

    /** 驳回：必填驳回理由 */
    public record RejectRequest(String reason) {}

    @PostMapping("/capability-proposals/{pid}/reject")
    public Map<String, Object> rejectProposal(@PathVariable long pid, @RequestBody RejectRequest req,
                                              HttpServletRequest request) {
        PermissionContext ctx = requireAdmin(request);
        if (req.reason() == null || req.reason().isBlank()) {
            throw new IllegalArgumentException("驳回理由必填");
        }
        int updated = controlJdbcTemplate.update(
                "UPDATE capability_proposal SET status = 'REJECTED', reject_reason = ?, reviewed_by = ?,"
                + " reviewed_at = CURRENT_TIMESTAMP(3) WHERE tenant_id = 'T1' AND id = ? AND status = 'PENDING'",
                req.reason(), ctx.username(), pid);
        return Map.of("success", updated > 0);
    }

    // ==================== 版本历史 ====================

    /** 版本历史列表（新到旧） */
    @GetMapping("/capabilities/{id}/versions")
    public List<Map<String, Object>> versions(@PathVariable String id, HttpServletRequest request) {
        requireAdmin(request);
        return registry.listVersions(id);
    }

    /** 指定版本快照 */
    @GetMapping("/capabilities/{id}/versions/{version}")
    public CapabilityDefinition versionSnapshot(@PathVariable String id, @PathVariable int version,
                                                HttpServletRequest request) {
        requireAdmin(request);
        return registry.getVersion(id, version)
                .orElseThrow(() -> ApiException.capabilityNotFound("版本不存在: " + id + "@v" + version));
    }

    // ==================== 反馈 / 审计（A4 雏形） ====================

    /** 反馈列表（评分 + traceId + 问题） */
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

    /** 运行审计列表 */
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

    // ==================== 审批单辅助 ====================

    /** 新建待审批单（payload 为定义快照，供审批人查看） */
    private void insertProposal(String capabilityId, String action, CapabilityDefinition def, String proposedBy) {
        try {
            controlJdbcTemplate.update(
                    "INSERT INTO capability_proposal (tenant_id, capability_id, action, payload, proposed_by)"
                    + " VALUES ('T1', ?, ?, ?, ?)",
                    capabilityId, action, camelMapper.writeValueAsString(def), proposedBy);
        } catch (Exception e) {
            throw new IllegalStateException("审批单写入失败: " + e.getMessage(), e);
        }
    }

    /** 是否存在已通过的审批单 */
    private boolean hasApprovedProposal(String capabilityId) {
        Integer c = controlJdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM capability_proposal WHERE tenant_id = 'T1' AND capability_id = ?"
                + " AND status = 'APPROVED'",
                Integer.class, capabilityId);
        return c != null && c > 0;
    }

    private PermissionContext requireAdmin(HttpServletRequest request) {
        PermissionContext ctx = (PermissionContext) request.getAttribute(AuthFilter.ATTR_PERMISSION);
        if (!ctx.isAdmin()) {
            throw ApiException.scopeDenied("需要管理员角色");
        }
        return ctx;
    }
}
