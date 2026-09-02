package com.dst.v2xagent.a2a;

import com.dst.v2xagent.agui.auth.AuthFilter;
import com.dst.v2xagent.common.ApiException;
import com.dst.v2xagent.common.PermissionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * A5 A2A 授权审批台 API：注册审批 + 最小授权 + credential 下发/轮换/吊销。
 * 全部端点要求 admin；secret 明文仅在下发/轮换响应中出现一次。
 */
@RestController
@RequestMapping("/admin/a2a")
@RequiredArgsConstructor
public class A2aAdminController {

    private final JdbcTemplate controlJdbcTemplate;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();

    /** 注册申请/已授权列表 */
    @GetMapping("/agents")
    public List<Map<String, Object>> agents(@RequestParam(required = false) String status,
                                            HttpServletRequest request) {
        requireAdmin(request);
        if (status != null && !status.isBlank()) {
            return controlJdbcTemplate.queryForList(
                    "SELECT * FROM a2a_agent WHERE tenant_id = 'T1' AND status = ? ORDER BY id DESC", status);
        }
        return controlJdbcTemplate.queryForList(
                "SELECT * FROM a2a_agent WHERE tenant_id = 'T1' ORDER BY id DESC");
    }

    public record CreateAgentRequest(String agentName, String description, String callbackUrl,
                                     List<String> requestedScopes) {}

    /** 调用方注册申请（admin 代录，生成 PENDING） */
    @PostMapping("/agents")
    public Map<String, Object> createAgent(@RequestBody CreateAgentRequest req, HttpServletRequest request) throws Exception {
        requireAdmin(request);
        if (req.agentName() == null || req.agentName().isBlank()) {
            throw ApiException.schemaInvalid("agentName 不能为空");
        }
        controlJdbcTemplate.update(
                "INSERT INTO a2a_agent (agent_name, description, callback_url, requested_scopes) VALUES (?,?,?,?)",
                req.agentName(), req.description(), req.callbackUrl(),
                mapper.writeValueAsString(req.requestedScopes() == null ? List.of() : req.requestedScopes()));
        Long id = controlJdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return Map.of("success", true, "id", id);
    }

    public record ApproveRequest(List<String> roles, List<String> scopes, String note) {}

    /** 审批通过：role + scope 最小授权 */
    @PostMapping("/agents/{id}/approve")
    public Map<String, Object> approve(@PathVariable long id, @RequestBody ApproveRequest req,
                                       HttpServletRequest request) throws Exception {
        PermissionContext ctx = requireAdmin(request);
        int n = controlJdbcTemplate.update(
                "UPDATE a2a_agent SET status = 'APPROVED', granted_roles = ?, granted_scopes = ?,"
                        + " review_note = ?, reviewed_by = ?, reviewed_at = NOW(3)"
                        + " WHERE id = ? AND tenant_id = 'T1' AND status = 'PENDING'",
                mapper.writeValueAsString(req.roles() == null ? List.of() : req.roles()),
                mapper.writeValueAsString(req.scopes() == null ? List.of() : req.scopes()),
                req.note(), ctx.userId(), id);
        if (n == 0) {
            throw ApiException.schemaInvalid("申请不存在或已处理");
        }
        return Map.of("success", true);
    }

    public record RejectRequest(String reason) {}

    /** 驳回（必填理由） */
    @PostMapping("/agents/{id}/reject")
    public Map<String, Object> reject(@PathVariable long id, @RequestBody RejectRequest req,
                                      HttpServletRequest request) {
        PermissionContext ctx = requireAdmin(request);
        if (req.reason() == null || req.reason().isBlank()) {
            throw ApiException.schemaInvalid("驳回必填理由");
        }
        int n = controlJdbcTemplate.update(
                "UPDATE a2a_agent SET status = 'REJECTED', review_note = ?, reviewed_by = ?, reviewed_at = NOW(3)"
                        + " WHERE id = ? AND tenant_id = 'T1' AND status = 'PENDING'",
                req.reason(), ctx.userId(), id);
        if (n == 0) {
            throw ApiException.schemaInvalid("申请不存在或已处理");
        }
        return Map.of("success", true);
    }

    /** 某个 Agent 的凭证列表（不含 secret） */
    @GetMapping("/agents/{id}/credentials")
    public List<Map<String, Object>> credentials(@PathVariable long id, HttpServletRequest request) {
        requireAdmin(request);
        return controlJdbcTemplate.queryForList(
                "SELECT id, agent_id, client_id, status, expires_at, created_at"
                        + " FROM a2a_credential WHERE agent_id = ? AND tenant_id = 'T1' ORDER BY id DESC", id);
    }

    /** 下发凭证：仅 APPROVED 可签发，secret 明文仅返回一次 */
    @PostMapping("/agents/{id}/credentials")
    public Map<String, Object> issue(@PathVariable long id, HttpServletRequest request) {
        requireAdmin(request);
        Map<String, Object> agent = controlJdbcTemplate.queryForMap(
                "SELECT status FROM a2a_agent WHERE id = ? AND tenant_id = 'T1'", id);
        if (!"APPROVED".equals(agent.get("status"))) {
            throw ApiException.schemaInvalid("仅审批通过的 Agent 可签发凭证");
        }
        return createCredential(id);
    }

    /** 轮换：旧凭证吊销 + 签发新凭证 */
    @PostMapping("/credentials/{id}/rotate")
    public Map<String, Object> rotate(@PathVariable long id, HttpServletRequest request) {
        requireAdmin(request);
        Map<String, Object> cred = controlJdbcTemplate.queryForMap(
                "SELECT agent_id, status FROM a2a_credential WHERE id = ? AND tenant_id = 'T1'", id);
        controlJdbcTemplate.update(
                "UPDATE a2a_credential SET status = 'REVOKED' WHERE id = ? AND tenant_id = 'T1'", id);
        long agentId = ((Number) cred.get("agent_id")).longValue();
        return createCredential(agentId);
    }

    /** 吊销 */
    @PostMapping("/credentials/{id}/revoke")
    public Map<String, Object> revoke(@PathVariable long id, HttpServletRequest request) {
        requireAdmin(request);
        int n = controlJdbcTemplate.update(
                "UPDATE a2a_credential SET status = 'REVOKED' WHERE id = ? AND tenant_id = 'T1' AND status = 'ACTIVE'", id);
        if (n == 0) {
            throw ApiException.schemaInvalid("凭证不存在或已吊销");
        }
        return Map.of("success", true);
    }

    /** 签发新凭证，明文 secret 仅本次返回 */
    private Map<String, Object> createCredential(long agentId) {
        String clientId = "ak_" + randomToken(12);
        String secret = "sk_" + randomToken(24);
        controlJdbcTemplate.update(
                "INSERT INTO a2a_credential (agent_id, client_id, secret_hash) VALUES (?,?,?)",
                agentId, clientId, sha256(secret));
        Long id = controlJdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return Map.of("success", true, "id", id, "clientId", clientId, "secret", secret);
    }

    private String randomToken(int bytes) {
        byte[] buf = new byte[bytes];
        random.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }

    private String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private PermissionContext requireAdmin(HttpServletRequest request) {
        PermissionContext ctx = (PermissionContext) request.getAttribute(AuthFilter.ATTR_PERMISSION);
        if (ctx == null || !ctx.isAdmin()) {
            throw ApiException.scopeDenied("需要管理员角色");
        }
        return ctx;
    }
}
