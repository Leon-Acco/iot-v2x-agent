package com.dst.v2xagent.geo;

import com.dst.v2xagent.capability.FleetMappingService;
import com.dst.v2xagent.common.ApiException;
import com.dst.v2xagent.common.PermissionContext;
import com.dst.v2xagent.observability.trace.TraceContext;
import com.dst.v2xagent.query.QueryBudget;
import com.dst.v2xagent.query.QueryExecutor;
import com.dst.v2xagent.query.QueryOutcome;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 地理引擎（Geo Engine）：省 → 市 → 区 三级车辆聚合
 * 统一在线口径：GREATEST(tbox.dev_time, position.report_time)，30 分钟内为在线
 * ACL：org 级行权限，admin 看全量；数据访问统一走 QueryExecutor。
 */
@Service
@RequiredArgsConstructor
public class GeoService {

    private final QueryExecutor queryExecutor;
    private final FleetMappingService fleetMappingService;

    /** 在线判定表达式（与孫生地图口径一致） */
    private static final String LEVEL_EXPR =
            " CASE WHEN GREATEST(COALESCE(t.dev_time, p.report_time), p.report_time) >= DATE_SUB(NOW(), INTERVAL 30 MINUTE) THEN 1 ELSE 0 END";

    /**
     * 区域聚合统计。
     * @param level  province / city / district
     * @param parent 上级区域名（city 必传省名，district 必传市名）
     */
    public List<Map<String, Object>> regionStats(String level, String parent, PermissionContext ctx) {
        String column = switch (level == null ? "province" : level) {
            case "province" -> "p.province_name";
            case "city" -> "p.city_name";
            case "district" -> "p.district_name";
            default -> throw ApiException.paramInvalid("不支持的地理层级: " + level);
        };
        String parentColumn = switch (level == null ? "province" : level) {
            case "city" -> "p.province_name";
            case "district" -> "p.city_name";
            default -> null;
        };

        Set<String> orgs = fleetMappingService.expandOrgNames(ctx);
        if (!ctx.isAdmin() && (orgs == null || orgs.isEmpty())) {
            throw ApiException.scopeDenied("当前账号没有任何车队数据权限");
        }

        StringBuilder sql = new StringBuilder();
        List<Object> binds = new ArrayList<>();
        sql.append("SELECT ").append(column).append(" AS region, COUNT(1) AS total,")
           .append(" SUM(").append(LEVEL_EXPR).append(") AS online")
           .append(" FROM vehicle_realtime_position p")
           .append(" LEFT JOIN (SELECT vin_code, MAX(dev_time) AS dev_time FROM tbox_realtime_vehicle_data GROUP BY vin_code) t")
           .append(" ON p.vin_code = t.vin_code")
           .append(" WHERE p.report_time > '2020-01-01' AND p.report_time < DATE_ADD(NOW(), INTERVAL 10 MINUTE)")
           .append(" AND ").append(column).append(" IS NOT NULL AND ").append(column).append(" != ''");
        if (parentColumn != null) {
            if (parent == null || parent.isBlank()) {
                throw ApiException.paramInvalid(level + " 层级必须指定上级区域 parent");
            }
            sql.append(" AND ").append(parentColumn).append(" = ?");
            binds.add(parent);
        }
        if (!ctx.isAdmin()) {
            sql.append(" AND p.vin_code IN (SELECT vin_code FROM basic_vehicle_info WHERE org_name IN (")
               .append(String.join(", ", orgs.stream().map(o -> "?").toList())).append("))");
            binds.addAll(orgs);
        }
        sql.append(" GROUP BY ").append(column).append(" ORDER BY total DESC LIMIT 500");

        try (TraceContext.Span span = TraceContext.span("geo", level)) {
            QueryOutcome outcome = queryExecutor.query(sql.toString(), binds,
                    new QueryBudget(8000, 500, 1_000_000L, 1), "geo." + level, ctx);
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object[] row : outcome.rows()) {
                Map<String, Object> item = new LinkedHashMap<>();
                long total = row[1] instanceof Number n ? n.longValue() : 0;
                long online = row[2] instanceof Number n ? n.longValue() : 0;
                item.put("region", row[0]);
                item.put("total", total);
                item.put("online", online);
                item.put("offline", total - online);
                item.put("online_rate", total == 0 ? null : Math.round(online * 1000.0 / total) / 10.0);
                out.add(item);
            }
            return out;
        }
    }
}
