package com.dst.v2xagent.agui.auth;

import com.dst.v2xagent.common.PermissionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 认证与权限解析（P0 本地账号 + HttpOnly Cookie 会话）
 * PermissionContext 只从服务端用户记录生成，forwardedProps 永不能扩大权限。
 */
@Repository
@RequiredArgsConstructor
public class AuthService {

    private final JdbcTemplate controlJdbcTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    /** P0 全部演示账号具备的只读 scope */
    private static final Set<String> READ_SCOPES = Set.of(
            "vehicle.status.read", "vehicle.location.read", "vehicle.mileage.read", "vehicle.alarm.read",
            "vehicle.fault.read", "vehicle.charge.read", "vehicle.trip.read", "vehicle.fence.read",
            // TBOX 新域能力（电池/冷链/锁车/安全驾驶）
            "vehicle.battery.read", "vehicle.coldchain.read", "vehicle.control.read", "vehicle.safety.read");

    /** 登录：返回会话 token，失败返回 empty */
    public Optional<String> login(String username, String password) {
        List<Map<String, Object>> users = controlJdbcTemplate.queryForList(
                "SELECT id, password_hash FROM sys_user WHERE tenant_id='T1' AND username=?", username);
        if (users.isEmpty()) return Optional.empty();
        if (!hash(password).equals(users.get(0).get("password_hash"))) return Optional.empty();
        String token = UUID.randomUUID().toString().replace("-", "");
        controlJdbcTemplate.update("""
                INSERT INTO user_session (token, user_id, expires_at)
                VALUES (?, ?, DATE_ADD(NOW(3), INTERVAL 12 HOUR))
                """, token, users.get(0).get("id"));
        return Optional.of(token);
    }

    /** 由 token 解析权限上下文（每次请求重新生成，不从会话记忆恢复） */
    @SuppressWarnings("unchecked")
    public Optional<PermissionContext> resolve(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        List<Map<String, Object>> rows = controlJdbcTemplate.queryForList("""
                SELECT u.id, u.tenant_id, u.username, u.roles, u.fleet_ids
                FROM user_session s JOIN sys_user u ON u.id = s.user_id
                WHERE s.token = ? AND s.expires_at > NOW(3)
                """, token);
        if (rows.isEmpty()) return Optional.empty();
        Map<String, Object> u = rows.get(0);
        try {
            Set<String> roles = new LinkedHashSet<>(mapper.readValue((String) u.get("roles"), List.class));
            Set<String> fleets = new LinkedHashSet<>(mapper.readValue((String) u.get("fleet_ids"), List.class));
            return Optional.of(new PermissionContext(
                    (String) u.get("tenant_id"),
                    ((Number) u.get("id")).longValue(),
                    (String) u.get("username"),
                    roles, READ_SCOPES, fleets));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 登出 */
    public void logout(String token) {
        controlJdbcTemplate.update("DELETE FROM user_session WHERE token = ?", token);
    }

    /**
     * 按用户名构建权限上下文（任务定时执行专用：无会话 token，按创建者当前权限重建 ACL）。
     * 用户不存在（被删，sys_user 无 status 列）返回 empty，由调度方判定任务失效。
     */
    @SuppressWarnings("unchecked")
    public Optional<PermissionContext> resolveByUsername(String username) {
        if (username == null || username.isBlank()) return Optional.empty();
        List<Map<String, Object>> rows = controlJdbcTemplate.queryForList(
                "SELECT id, tenant_id, username, roles, fleet_ids FROM sys_user WHERE tenant_id='T1' AND username=?",
                username);
        if (rows.isEmpty()) return Optional.empty();
        Map<String, Object> u = rows.get(0);
        try {
            Set<String> roles = new LinkedHashSet<>(mapper.readValue((String) u.get("roles"), List.class));
            Set<String> fleets = new LinkedHashSet<>(mapper.readValue((String) u.get("fleet_ids"), List.class));
            return Optional.of(new PermissionContext(
                    (String) u.get("tenant_id"),
                    ((Number) u.get("id")).longValue(),
                    (String) u.get("username"),
                    roles, READ_SCOPES, fleets));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 密码哈希（P0 演示：SHA-256 + 固定盐；生产接 OIDC/网关身份） */
    public static String hash(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(("v2x-agent:" + password).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
