package com.dst.v2xagent.common;

import java.util.Set;

/**
 * 权限上下文（安全命门）
 * 每次请求从可信身份与服务端授权数据生成，不从会话记忆恢复；
 * 业务上下文中的 VIN/车队只能与其求交收窄，不能扩大。
 */
public record PermissionContext(
        String tenantId,
        Long userId,
        String username,
        Set<String> roles,
        Set<String> scopes,
        Set<String> fleetIds
) {
    /** 判断是否为管理员角色 */
    public boolean isAdmin() {
        return roles != null && roles.contains("admin");
    }

    /** 权限指纹：进缓存 key，车队/角色变化自动失效 */
    public String fingerprint() {
        return tenantId + "|" + userId + "|" + String.join(",", sorted(roles)) + "|" + String.join(",", sorted(fleetIds));
    }

    private static java.util.List<String> sorted(Set<String> s) {
        return s == null ? java.util.List.of() : s.stream().sorted().toList();
    }
}
