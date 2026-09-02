package com.dst.v2xagent.capability;

import com.dst.v2xagent.common.PermissionContext;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 车队 → Doris 组织映射（V4 fleet_org_mapping）。
 * 用户 fleetIds（F001 等）映射为 basic_vehicle_info.org_name，
 * 未命中的 token 原样透传（兼容直接传入 org 名称）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FleetMappingService {

    private final JdbcTemplate controlJdbcTemplate;

    /** 映射缓存（60s：管理后台改映射快速生效） */
    private final Cache<String, Map<String, String>> mappingCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60))
            .build();

    /** 展开为 org 名称集合（保序去重；无权限返回空集，上层 fail closed） */
    public Set<String> expandOrgNames(PermissionContext ctx) {
        if (ctx.fleetIds() == null || ctx.fleetIds().isEmpty()) {
            return Set.of();
        }
        Map<String, String> mapping = loadMapping(ctx.tenantId());
        Set<String> orgs = new LinkedHashSet<>();
        for (String fleetId : ctx.fleetIds()) {
            orgs.add(mapping.getOrDefault(fleetId, fleetId));
        }
        return orgs;
    }

    /** 加载映射（缓存失效时直查库，异常降级为空映射即原样透传） */
    private Map<String, String> loadMapping(String tenantId) {
        try {
            return mappingCache.get(tenantId, t -> {
                Map<String, String> map = new ConcurrentHashMap<>();
                controlJdbcTemplate.query(
                        "SELECT fleet_id, org_name FROM fleet_org_mapping WHERE tenant_id = ?",
                        rs -> {
                            map.put(rs.getString("fleet_id"), rs.getString("org_name"));
                        }, t);
                return map;
            });
        } catch (Exception e) {
            log.warn("车队映射加载失败，降级为原样透传: {}", e.getMessage());
            return Map.of();
        }
    }
}
