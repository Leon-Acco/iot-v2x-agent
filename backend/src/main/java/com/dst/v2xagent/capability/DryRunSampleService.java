package com.dst.v2xagent.capability;

import com.dst.v2xagent.capability.model.CapabilityDefinition;
import com.dst.v2xagent.common.PermissionContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 试跑样例参数生成器：让管理后台 dry-run 开箱有数据
 * 按参数定义生成默认值（daterange→近N天、枚举→首项），
 * 车辆参数从 basic_vehicle_info 取当前账号权限内的真实车牌/VIN。
 */
@Slf4j
@Component
public class DryRunSampleService {

    private final JdbcTemplate analyticsJdbcTemplate;
    private final FleetMappingService fleetMappingService;

    /** 显式注入分析库 JdbcTemplate（与 QueryExecutor 同源） */
    public DryRunSampleService(@Qualifier("analyticsJdbcTemplate") JdbcTemplate analyticsJdbcTemplate,
                               FleetMappingService fleetMappingService) {
        this.analyticsJdbcTemplate = analyticsJdbcTemplate;
        this.fleetMappingService = fleetMappingService;
    }

    /** 按参数定义生成样例参数（只填有意义的值，可选参数不乱填） */
    public Map<String, Object> sampleParams(CapabilityDefinition def, PermissionContext ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String[]> vehicles = null;
        for (CapabilityDefinition.ParamDef p : def.getParams()) {
            switch (p.getType()) {
                case "daterange" -> {
                    int span = p.getMaxSpanDays() != null && p.getMaxSpanDays() < 7 ? p.getMaxSpanDays() : 7;
                    out.put(p.getName(), "近" + span + "天");
                }
                case "array<string>" -> {
                    if ("vin_list".equals(p.getName())) {
                        vehicles = vehicles != null ? vehicles : loadVehicles(ctx);
                        out.put(p.getName(), vehicles.stream().map(v -> v[0]).limit(3).toList());
                    }
                }
                case "string" -> {
                    if ("vehicle".equals(p.getName())) {
                        vehicles = vehicles != null ? vehicles : loadVehicles(ctx);
                        if (!vehicles.isEmpty()) {
                            String[] v = vehicles.get(0);
                            out.put(p.getName(), v[1] != null && !v[1].isBlank() ? v[1] : v[0]);
                        }
                    } else if (p.getEnumValues() != null && !p.getEnumValues().isEmpty()) {
                        out.put(p.getName(), p.getEnumValues().get(0));
                    }
                }
                case "int", "number" -> out.put(p.getName(), 1);
                case "boolean" -> out.put(p.getName(), true);
                default -> {
                    // 未知类型不填
                }
            }
        }
        return out;
    }

    /** 权限内真实车辆（vin + 车牌），最多 5 台；查询失败降级为空集 */
    private List<String[]> loadVehicles(PermissionContext ctx) {
        try {
            Set<String> orgs = fleetMappingService.expandOrgNames(ctx);
            if (orgs.isEmpty()) {
                return List.of();
            }
            String marks = orgs.stream().map(o -> "?").collect(Collectors.joining(","));
            return analyticsJdbcTemplate.query(
                    "SELECT vin_code, car_no FROM basic_vehicle_info WHERE org_name IN (" + marks + ")"
                    + " AND car_no IS NOT NULL AND car_no <> '' LIMIT 5",
                    (rs, i) -> new String[]{rs.getString("vin_code"), rs.getString("car_no")},
                    orgs.toArray());
        } catch (Exception e) {
            log.warn("样例车辆查询失败: {}", e.getMessage());
            return List.of();
        }
    }
}
