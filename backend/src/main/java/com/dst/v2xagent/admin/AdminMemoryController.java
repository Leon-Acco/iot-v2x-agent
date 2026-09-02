package com.dst.v2xagent.admin;

import com.dst.v2xagent.agui.auth.AuthFilter;
import com.dst.v2xagent.common.PermissionContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * A3 记忆治理台（设计文档 §8.6）：提议审核 → 通过则入 memory_item + ACL。
 * 仅管理员；每条记忆可追溯（source_trace_id / proposed_by / approved_by）。
 */
@Slf4j
@RestController
@RequestMapping("/admin/memory")
@RequiredArgsConstructor
public class AdminMemoryController {

    private final JdbcTemplate controlJdbcTemplate;

    /** 待审提议列表 */
    @GetMapping("/proposals")
    public List<Map<String, Object>> proposals(HttpServletRequest request) {
        requireAdmin(request);
        return controlJdbcTemplate.queryForList(
                "SELECT id, scope_type, scope_key, content, source_trace_id, proposed_by, status, created_at"
                + " FROM memory_proposal WHERE tenant_id = 'T1' ORDER BY id DESC LIMIT 100");
    }

    /** 通过：转正为 ACTIVE 记忆 + 根据提议人车队写 ACL */
    @PostMapping("/proposals/{id}/approve")
    public Map<String, Object> approve(@PathVariable long id, HttpServletRequest request) {
        PermissionContext ctx = requireAdmin(request);
        List<Map<String, Object>> rows = controlJdbcTemplate.queryForList(
                "SELECT * FROM memory_proposal WHERE tenant_id = 'T1' AND id = ? AND status = 'PENDING'", id);
        if (rows.isEmpty()) {
            return Map.of("success", false, "message", "提议不存在或已处理");
        }
        Map<String, Object> p = rows.get(0);
        controlJdbcTemplate.update(
                "INSERT INTO memory_item (tenant_id, scope_type, scope_key, content, content_index,"
                + " source_trace_id, proposed_by, approved_by, evidence, expire_at)"
                + " VALUES ('T1', ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                p.get("scope_type"), p.get("scope_key"), p.get("content"), p.get("content"),
                p.get("source_trace_id"), p.get("proposed_by"), ctx.username(),
                p.get("evidence"), p.get("expire_at"));
        Long memoryId = controlJdbcTemplate.queryForObject(
                "SELECT id FROM memory_item WHERE tenant_id = 'T1' ORDER BY id DESC LIMIT 1", Long.class);

        // ACL：提议人的 user token + 其车队 token（从用户表读取）
        List<Map<String, Object>> users = controlJdbcTemplate.queryForList(
                "SELECT id, roles, fleet_ids FROM sys_user WHERE tenant_id = 'T1' AND username = ?",
                p.get("proposed_by"));
        if (!users.isEmpty()) {
            Map<String, Object> u = users.get(0);
            insertAcl(memoryId, "user:" + u.get("id"));
            for (String token : parseJsonArray(String.valueOf(u.get("roles")))) {
                insertAcl(memoryId, "role:" + token);
            }
            for (String token : parseJsonArray(String.valueOf(u.get("fleet_ids")))) {
                insertAcl(memoryId, "fleet:" + token);
            }
        }
        controlJdbcTemplate.update(
                "UPDATE memory_proposal SET status = 'APPROVED', reviewed_by = ?, reviewed_at = CURRENT_TIMESTAMP(3)"
                + " WHERE tenant_id = 'T1' AND id = ?", ctx.username(), id);
        log.info("记忆提议已通过: proposal={} memory={} by={}", id, memoryId, ctx.username());
        return Map.of("success", true, "memoryId", memoryId == null ? -1 : memoryId);
    }

    /** 驳回：必填驳回理由 */
    public record RejectRequest(@NotBlank String reason) {}

    @PostMapping("/proposals/{id}/reject")
    public Map<String, Object> reject(@PathVariable long id, @RequestBody RejectRequest req,
                                      HttpServletRequest request) {
        PermissionContext ctx = requireAdmin(request);
        int updated = controlJdbcTemplate.update(
                "UPDATE memory_proposal SET status = 'REJECTED', reject_reason = ?, reviewed_by = ?,"
                + " reviewed_at = CURRENT_TIMESTAMP(3) WHERE tenant_id = 'T1' AND id = ? AND status = 'PENDING'",
                req.reason(), ctx.username(), id);
        return Map.of("success", updated > 0);
    }

    /** P6 记忆库列表（ACL 编辑入口） */
    @GetMapping("/items")
    public List<Map<String, Object>> items(@org.springframework.web.bind.annotation.RequestParam(required = false) String keyword,
                                           HttpServletRequest request) {
        requireAdmin(request);
        if (keyword != null && !keyword.isBlank()) {
            return controlJdbcTemplate.queryForList(
                    "SELECT id, scope_type, scope_key, content, status, proposed_by, approved_by, expire_at, created_at"
                    + " FROM memory_item WHERE tenant_id = 'T1' AND content LIKE ? ORDER BY id DESC LIMIT 100",
                    "%" + keyword + "%");
        }
        return controlJdbcTemplate.queryForList(
                "SELECT id, scope_type, scope_key, content, status, proposed_by, approved_by, expire_at, created_at"
                + " FROM memory_item WHERE tenant_id = 'T1' ORDER BY id DESC LIMIT 100");
    }

    /** P6 某条记忆的 ACL token 列表 */
    @GetMapping("/items/{id}/acl")
    public List<Map<String, Object>> acl(@PathVariable long id, HttpServletRequest request) {
        requireAdmin(request);
        return controlJdbcTemplate.queryForList(
                "SELECT id, principal_token, created_at FROM memory_acl WHERE tenant_id = 'T1' AND memory_id = ?", id);
    }

    public record AclRequest(@NotBlank String principalToken) {}

    /** P6 新增 ACL：仅允许 user:/role:/fleet:/global: 四类前缀 */
    @PostMapping("/items/{id}/acl")
    public Map<String, Object> addAcl(@PathVariable long id, @RequestBody AclRequest req,
                                      HttpServletRequest request) {
        requireAdmin(request);
        String token = req.principalToken().trim();
        boolean ok = token.startsWith("user:") || token.startsWith("role:")
                || token.startsWith("fleet:") || token.startsWith("global:");
        if (!ok || token.length() > 100) {
            throw com.dst.v2xagent.common.ApiException.schemaInvalid(
                    "非法 token，仅支持 user:/role:/fleet:/global: 前缀");
        }
        insertAcl(id, token);
        return Map.of("success", true);
    }

    /** P6 删除 ACL token */
    @org.springframework.web.bind.annotation.DeleteMapping("/items/{id}/acl")
    public Map<String, Object> removeAcl(@PathVariable long id,
                                         @org.springframework.web.bind.annotation.RequestParam String token,
                                         HttpServletRequest request) {
        requireAdmin(request);
        controlJdbcTemplate.update(
                "DELETE FROM memory_acl WHERE tenant_id = 'T1' AND memory_id = ? AND principal_token = ?",
                id, token);
        return Map.of("success", true);
    }

    /** P6 发起删除申请（不直接删） */
    public record RemovalRequest(@NotBlank String reason) {}

    @PostMapping("/items/{id}/removal-requests")
    public Map<String, Object> requestRemoval(@PathVariable long id, @RequestBody RemovalRequest req,
                                              HttpServletRequest request) {
        PermissionContext ctx = requireAdmin(request);
        controlJdbcTemplate.update(
                "INSERT INTO memory_removal_request (memory_id, reason, requested_by) VALUES (?,?,?)",
                id, req.reason(), ctx.username());
        return Map.of("success", true);
    }

    /** P6 删除申请列表（带记忆摘要） */
    @GetMapping("/removal-requests")
    public List<Map<String, Object>> removalRequests(HttpServletRequest request) {
        requireAdmin(request);
        return controlJdbcTemplate.queryForList(
                "SELECT r.id, r.memory_id, r.reason, r.requested_by, r.status, r.resolved_by, r.created_at,"
                + " LEFT(m.content, 80) AS content_preview"
                + " FROM memory_removal_request r"
                + " LEFT JOIN memory_item m ON m.id = r.memory_id AND m.tenant_id = 'T1'"
                + " WHERE r.tenant_id = 'T1' ORDER BY r.id DESC LIMIT 100");
    }

    /** P6 处理申请：delete -> 记忆归档，keep -> 保留 */
    public record ResolveRequest(@NotBlank String action) {}

    @PostMapping("/removal-requests/{id}/resolve")
    public Map<String, Object> resolveRemoval(@PathVariable long id, @RequestBody ResolveRequest req,
                                              HttpServletRequest request) {
        PermissionContext ctx = requireAdmin(request);
        boolean delete = "delete".equals(req.action());
        if (!delete && !"keep".equals(req.action())) {
            throw com.dst.v2xagent.common.ApiException.schemaInvalid("action 仅支持 delete/keep");
        }
        List<Map<String, Object>> rows = controlJdbcTemplate.queryForList(
                "SELECT memory_id FROM memory_removal_request WHERE tenant_id = 'T1' AND id = ? AND status = 'PENDING'", id);
        if (rows.isEmpty()) {
            return Map.of("success", false, "message", "申请不存在或已处理");
        }
        if (delete) {
            controlJdbcTemplate.update(
                    "UPDATE memory_item SET status = 'ARCHIVED' WHERE tenant_id = 'T1' AND id = ?",
                    rows.get(0).get("memory_id"));
        }
        controlJdbcTemplate.update(
                "UPDATE memory_removal_request SET status = ?, resolved_by = ?, resolved_at = CURRENT_TIMESTAMP(3)"
                + " WHERE tenant_id = 'T1' AND id = ?",
                delete ? "DELETED" : "KEPT", ctx.username(), id);
        return Map.of("success", true);
    }

    private void insertAcl(long memoryId, String principalToken) {
        controlJdbcTemplate.update(
                "INSERT IGNORE INTO memory_acl (tenant_id, memory_id, principal_token) VALUES ('T1', ?, ?)",
                memoryId, principalToken);
    }

    /** JSON 数组简易解析（["a","b"] 形式） */
    private List<String> parseJsonArray(String json) {
        String cleaned = json == null ? "" : json.replaceAll("[\\[\\]\"\\s]", "");
        if (cleaned.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.asList(cleaned.split(","));
    }

    private PermissionContext requireAdmin(HttpServletRequest request) {
        PermissionContext ctx = (PermissionContext) request.getAttribute(AuthFilter.ATTR_PERMISSION);
        if (ctx == null || !ctx.isAdmin()) {
            throw com.dst.v2xagent.common.ApiException.scopeDenied("仅管理员可用");
        }
        return ctx;
    }
}
