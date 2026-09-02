package com.dst.v2xagent.map;

import com.dst.v2xagent.agui.auth.AuthFilter;
import com.dst.v2xagent.capability.FleetMappingService;
import com.dst.v2xagent.common.PermissionContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Vehicle map data endpoints (P1 dashboard), org-scoped.
 * Fix log (2026-09-01):
 * 1. tbox join switched to a pre-aggregated subquery (no row fan-out)
 * 2. online_level: unified GREATEST(tbox.dev_time, position.report_time), 3 tiers
 * 3. new fleet-summary aggregate endpoint so KPIs do not rely on the LIMIT subset
 * 4. simple 30s cache keyed by permission scope
 * 5. future-time tolerance tightened from +1 day to +10 minutes
 */
@Slf4j
@RestController
@RequestMapping("/ag-ui/map")
public class MapDataController {

    private final JdbcTemplate analyticsJdbcTemplate;
    private final FleetMappingService fleetMappingService;

    /** Explicit constructor: Lombok does not copy @Qualifier (multi-datasource pitfall) */
    public MapDataController(@Qualifier("analyticsJdbcTemplate") JdbcTemplate analyticsJdbcTemplate,
                             FleetMappingService fleetMappingService) {
        this.analyticsJdbcTemplate = analyticsJdbcTemplate;
        this.fleetMappingService = fleetMappingService;
    }

    private record CachedResult(long at, Object data) {}

    private final Map<String, CachedResult> cache = new ConcurrentHashMap<>();
    private static final long TTL_MS = 30_000L;

    @SuppressWarnings("unchecked")
    private <T> T cached(String key, Supplier<T> supplier) {
        CachedResult c = cache.get(key);
        long now = System.currentTimeMillis();
        if (c != null && now - c.at() < TTL_MS) {
            return (T) c.data();
        }
        T v = supplier.get();
        cache.put(key, new CachedResult(now, v));
        return v;
    }

    private record Acl(String where, Object[] args) {}

    private Acl acl(PermissionContext ctx, Set<String> orgs) {
        String vinFilter = ctx.isAdmin() ? "1=1"
                : "p.vin_code IN (SELECT vin_code FROM basic_vehicle_info WHERE org_name IN ("
                + String.join(", ", orgs.stream().map(o -> "?").toList()) + "))";
        String where = vinFilter
                + " AND p.report_time > '2020-01-01' AND p.report_time < DATE_ADD(NOW(), INTERVAL 10 MINUTE)"
                + " AND p.gcj02_lng > 70 AND p.gcj02_lng < 140"
                + " AND p.gcj02_lat > 15 AND p.gcj02_lat < 55";
        return new Acl(where, ctx.isAdmin() ? new Object[0] : orgs.toArray());
    }

    private static final String TBOX_JOIN =
            " LEFT JOIN (SELECT vin_code, MAX(dev_time) AS dev_time"
            + " FROM tbox_realtime_vehicle_data GROUP BY vin_code) t ON p.vin_code = t.vin_code";

    private static final String LEVEL_EXPR =
            " CASE WHEN GREATEST(COALESCE(t.dev_time, p.report_time), p.report_time) >= DATE_SUB(NOW(), INTERVAL 30 MINUTE) THEN 0"
            + " WHEN GREATEST(COALESCE(t.dev_time, p.report_time), p.report_time) >= DATE_SUB(NOW(), INTERVAL 1 DAY) THEN 1"
            + " ELSE 2 END";

    private String cacheScope(PermissionContext ctx, Set<String> orgs) {
        return ctx.isAdmin() ? "admin" : String.join(",", orgs.stream().sorted().toList());
    }

    /** latest vehicle positions (GCJ-02) + unified online tier, org-scoped */
    @GetMapping("/vehicles")
    public List<Map<String, Object>> vehicles(HttpServletRequest request) {
        PermissionContext ctx = (PermissionContext) request.getAttribute(AuthFilter.ATTR_PERMISSION);
        Set<String> orgs = fleetMappingService.expandOrgNames(ctx);
        if (!ctx.isAdmin() && orgs.isEmpty()) {
            return List.of();
        }
        final PermissionContext fctx = ctx;
        return cached("vehicles:" + cacheScope(ctx, orgs), () -> {
            Acl acl = acl(fctx, orgs);
            String sql = "SELECT p.vin_code AS vin, COALESCE(NULLIF(p.car_no, ''), p.vin_code) AS plate_no,"
                    + " ROUND(p.gcj02_lng, 6) AS lng, ROUND(p.gcj02_lat, 6) AS lat,"
                    + " COALESCE(p.province_name, '') AS province_name, COALESCE(p.city_name, '') AS city_name,"
                    + " COALESCE(p.district_name, '') AS district_name,"
                    + " COALESCE(p.car_brand_name, '') AS car_brand_name, COALESCE(p.car_model_name, '') AS car_model_name,"
                    + " p.mileage, p.report_time,"
                    + LEVEL_EXPR + " AS online_level,"
                    + " CASE WHEN" + LEVEL_EXPR + " = 0 THEN 1 ELSE 0 END AS online"
                    + " FROM vehicle_realtime_position p" + TBOX_JOIN
                    + " WHERE " + acl.where()
                    + " ORDER BY p.report_time DESC LIMIT 2000";
            return analyticsJdbcTemplate.queryForList(sql, acl.args());
        });
    }

    /** fleet KPI summary: total / tier counts / mileage / data freshness (stale guard) */
    @GetMapping("/fleet-summary")
    public Map<String, Object> fleetSummary(HttpServletRequest request) {
        PermissionContext ctx = (PermissionContext) request.getAttribute(AuthFilter.ATTR_PERMISSION);
        Set<String> orgs = fleetMappingService.expandOrgNames(ctx);
        if (!ctx.isAdmin() && orgs.isEmpty()) {
            return Map.of("total", 0, "run", 0, "park", 0, "lost", 0,
                    "mileage_sum", 0, "stale", true);
        }
        final PermissionContext fctx = ctx;
        return cached("summary:" + cacheScope(ctx, orgs), () -> {
            Acl acl = acl(fctx, orgs);
            String sql = "SELECT COUNT(*) AS total,"
                    + " SUM(CASE WHEN g >= DATE_SUB(NOW(), INTERVAL 30 MINUTE) THEN 1 ELSE 0 END) AS run,"
                    + " SUM(CASE WHEN g < DATE_SUB(NOW(), INTERVAL 30 MINUTE) AND g >= DATE_SUB(NOW(), INTERVAL 1 DAY) THEN 1 ELSE 0 END) AS park,"
                    + " SUM(CASE WHEN g < DATE_SUB(NOW(), INTERVAL 1 DAY) THEN 1 ELSE 0 END) AS lost,"
                    + " COALESCE(SUM(x.mileage), 0) AS mileage_sum,"
                    + " MAX(x.report_time) AS max_report_time"
                    + " FROM (SELECT p.mileage, p.report_time,"
                    + " GREATEST(COALESCE(t.dev_time, p.report_time), p.report_time) AS g"
                    + " FROM vehicle_realtime_position p" + TBOX_JOIN
                    + " WHERE " + acl.where() + ") x";
            Map<String, Object> row = analyticsJdbcTemplate.queryForMap(sql, acl.args());
            Object maxRt = row.get("max_report_time");
            long maxMs = -1;
            if (maxRt instanceof java.sql.Timestamp ts) {
                maxMs = ts.getTime();
            } else if (maxRt instanceof java.time.LocalDateTime ldt) {
                maxMs = ldt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
            }
            boolean stale = maxMs < 0 || maxMs < System.currentTimeMillis() - 2 * 3600_000L;
            row.put("stale", stale);
            return row;
        });
    }

    /** province aggregation: totals / online + brand Top10 (org-scoped) */
    @GetMapping("/province-stats")
    public Map<String, Object> provinceStats(HttpServletRequest request) {
        PermissionContext ctx = (PermissionContext) request.getAttribute(AuthFilter.ATTR_PERMISSION);
        Set<String> orgs = fleetMappingService.expandOrgNames(ctx);
        if (!ctx.isAdmin() && orgs.isEmpty()) {
            return Map.of("provinces", List.of(), "brands", List.of());
        }
        final PermissionContext fctx = ctx;
        return cached("prov:" + cacheScope(ctx, orgs), () -> {
            Acl acl = acl(fctx, orgs);
            String provinceSql = "SELECT COALESCE(NULLIF(p.province_name, ''), '未知') AS province_name,"
                    + " COUNT(*) AS total,"
                    + " SUM(CASE WHEN" + LEVEL_EXPR + " = 0 THEN 1 ELSE 0 END) AS online"
                    + " FROM vehicle_realtime_position p" + TBOX_JOIN
                    + " WHERE " + acl.where()
                    + " GROUP BY COALESCE(NULLIF(p.province_name, ''), '未知')"
                    + " ORDER BY total DESC";
            List<Map<String, Object>> provinces = analyticsJdbcTemplate.queryForList(provinceSql, acl.args());

            String brandSql = "SELECT COALESCE(NULLIF(p.car_brand_name, ''), '未知') AS brand, COUNT(*) AS cnt"
                    + " FROM vehicle_realtime_position p"
                    + " WHERE " + acl.where()
                    + " GROUP BY COALESCE(NULLIF(p.car_brand_name, ''), '未知')"
                    + " ORDER BY cnt DESC LIMIT 10";
            List<Map<String, Object>> brands = analyticsJdbcTemplate.queryForList(brandSql, acl.args());

            return Map.of("provinces", provinces, "brands", brands);
        });
    }

    /** region aggregation: province -> city -> district drill-down, org-scoped */
    @GetMapping("/region-stats")
    public List<Map<String, Object>> regionStats(@RequestParam(defaultValue = "province") String level,
                                                 @RequestParam(required = false) String province,
                                                 @RequestParam(required = false) String city,
                                                 HttpServletRequest request) {
        PermissionContext ctx = (PermissionContext) request.getAttribute(AuthFilter.ATTR_PERMISSION);
        Set<String> orgs = fleetMappingService.expandOrgNames(ctx);
        if (!ctx.isAdmin() && orgs.isEmpty()) {
            return List.of();
        }
        final String col;
        if ("city".equals(level)) {
            col = "city_name";
        } else if ("district".equals(level)) {
            col = "district_name";
        } else {
            col = "province_name";
        }
        final PermissionContext fctx = ctx;
        return cached("region:" + level + "|" + (province == null ? "" : province) + "|"
                + (city == null ? "" : city) + "|" + cacheScope(ctx, orgs), () -> {
            Acl acl = acl(fctx, orgs);
            StringBuilder where = new StringBuilder(acl.where());
            List<Object> args = new java.util.ArrayList<>(java.util.Arrays.asList(acl.args()));
            if (province != null && !province.isBlank() && !"province_name".equals(col)) {
                where.append(" AND p.province_name = ?");
                args.add(province);
            }
            if (city != null && !city.isBlank() && "district_name".equals(col)) {
                where.append(" AND p.city_name = ?");
                args.add(city);
            }
            String sql = "SELECT COALESCE(NULLIF(p." + col + ", ''), 'N/A') AS name,"
                    + " COUNT(*) AS total,"
                    + " SUM(CASE WHEN" + LEVEL_EXPR + " = 0 THEN 1 ELSE 0 END) AS online"
                    + " FROM vehicle_realtime_position p" + TBOX_JOIN
                    + " WHERE " + where
                    + " GROUP BY COALESCE(NULLIF(p." + col + ", ''), 'N/A')"
                    + " ORDER BY total DESC";
            return analyticsJdbcTemplate.queryForList(sql, args.toArray());
        });
    }

    /** search vehicle point by plate number or VIN (fuzzy), org-scoped */
    @GetMapping("/search")
    public List<Map<String, Object>> search(@RequestParam String keyword, HttpServletRequest request) {
        PermissionContext ctx = (PermissionContext) request.getAttribute(AuthFilter.ATTR_PERMISSION);
        Set<String> orgs = fleetMappingService.expandOrgNames(ctx);
        if (!ctx.isAdmin() && orgs.isEmpty()) {
            return List.of();
        }
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.length() < 2) {
            return List.of();
        }
        Acl acl = acl(ctx, orgs);
        String sql = "SELECT p.vin_code AS vin, COALESCE(NULLIF(p.car_no, ''), p.vin_code) AS plate_no,"
                + " ROUND(p.gcj02_lng, 6) AS lng, ROUND(p.gcj02_lat, 6) AS lat,"
                + " COALESCE(p.province_name, '') AS province_name, COALESCE(p.city_name, '') AS city_name,"
                + " COALESCE(p.district_name, '') AS district_name,"
                + " COALESCE(p.car_brand_name, '') AS car_brand_name, COALESCE(p.car_model_name, '') AS car_model_name,"
                + " p.mileage, p.report_time,"
                + LEVEL_EXPR + " AS online_level,"
                + " CASE WHEN" + LEVEL_EXPR + " = 0 THEN 1 ELSE 0 END AS online"
                + " FROM vehicle_realtime_position p" + TBOX_JOIN
                + " WHERE " + acl.where()
                + " AND (p.car_no LIKE CONCAT('%', ?, '%') OR p.vin_code LIKE CONCAT('%', ?, '%'))"
                + " ORDER BY p.report_time DESC LIMIT 20";
        List<Object> args = new java.util.ArrayList<>(java.util.Arrays.asList(acl.args()));
        args.add(kw);
        args.add(kw);
        return analyticsJdbcTemplate.queryForList(sql, args.toArray());
    }

    // ===================== P2 cockpit: alerts + overview =====================

    /** alert ACL: same org scoping, alarm table alias a (args appended in order) */
    private String alertAcl(PermissionContext ctx, Set<String> orgs, List<Object> args) {
        if (ctx.isAdmin()) {
            return "1=1";
        }
        args.addAll(orgs);
        return "a.car_vin IN (SELECT vin_code FROM basic_vehicle_info WHERE org_name IN ("
                + String.join(", ", orgs.stream().map(o -> "?").toList()) + "))";
    }

    /** realtime alert list: last 24h, latest first, joined with latest position, org-scoped */
    @GetMapping("/alerts")
    public List<Map<String, Object>> alerts(@RequestParam(defaultValue = "20") int limit,
                                            HttpServletRequest request) {
        PermissionContext ctx = (PermissionContext) request.getAttribute(AuthFilter.ATTR_PERMISSION);
        Set<String> orgs = fleetMappingService.expandOrgNames(ctx);
        if (!ctx.isAdmin() && orgs.isEmpty()) {
            return List.of();
        }
        int lim = Math.min(Math.max(limit, 1), 100);
        List<Object> args = new java.util.ArrayList<>();
        String where = alertAcl(ctx, orgs, args);
        args.add(lim);
        String sql = "SELECT a.car_vin AS vin, COALESCE(NULLIF(b.car_no, ''), a.car_vin) AS plate_no,"
                + " a.alarm_type, a.risk_level, a.alarm_start_time, a.alarm_end_time,"
                + " COALESCE(p.province_name, '') AS province_name, COALESCE(p.city_name, '') AS city_name,"
                + " p.gcj02_lng AS lng, p.gcj02_lat AS lat"
                + " FROM adas_aggregation_simple_alarm_record a"
                + " LEFT JOIN basic_vehicle_info b ON a.car_vin = b.vin_code"
                + " LEFT JOIN vehicle_realtime_position p ON a.car_vin = p.vin_code"
                + " WHERE " + where
                + " AND a.alarm_start_time >= DATE_SUB(NOW(), INTERVAL 24 HOUR)"
                + " AND a.alarm_start_time < DATE_ADD(NOW(), INTERVAL 10 MINUTE)"
                + " ORDER BY a.alarm_start_time DESC LIMIT ?";
        return analyticsJdbcTemplate.queryForList(sql, args.toArray());
    }

    /** one-shot cockpit payload: KPI + status tiers + coverage + alert agg/trend + region/brand top, org-scoped */
    @GetMapping("/overview")
    public Map<String, Object> overview(HttpServletRequest request) {
        PermissionContext ctx = (PermissionContext) request.getAttribute(AuthFilter.ATTR_PERMISSION);
        Set<String> orgs = fleetMappingService.expandOrgNames(ctx);
        if (!ctx.isAdmin() && orgs.isEmpty()) {
            return Map.ofEntries(Map.entry("totalVehicles", 0), Map.entry("onlineVehicles", 0),
                    Map.entry("parkVehicles", 0), Map.entry("lostVehicles", 0),
                    Map.entry("mileageSum", 0), Map.entry("coverageProvince", 0), Map.entry("coverageCity", 0),
                    Map.entry("alertTotal", 0), Map.entry("alertSevere", 0),
                    Map.entry("alertTrend", List.of()), Map.entry("regionTop", List.of()), Map.entry("brandTop", List.of()));
        }
        final PermissionContext fctx = ctx;
        return cached("overview:" + cacheScope(ctx, orgs), () -> {
            Map<String, Object> out = new java.util.HashMap<>();
            Acl acl = acl(fctx, orgs);

            // vehicle KPI + tiers (same caliber as fleet-summary)
            String sumSql = "SELECT COUNT(*) AS total,"
                    + " SUM(CASE WHEN g >= DATE_SUB(NOW(), INTERVAL 30 MINUTE) THEN 1 ELSE 0 END) AS run,"
                    + " SUM(CASE WHEN g < DATE_SUB(NOW(), INTERVAL 30 MINUTE) AND g >= DATE_SUB(NOW(), INTERVAL 1 DAY) THEN 1 ELSE 0 END) AS park,"
                    + " SUM(CASE WHEN g < DATE_SUB(NOW(), INTERVAL 1 DAY) THEN 1 ELSE 0 END) AS lost,"
                    + " COALESCE(SUM(x.mileage), 0) AS mileage_sum"
                    + " FROM (SELECT p.mileage,"
                    + " GREATEST(COALESCE(t.dev_time, p.report_time), p.report_time) AS g"
                    + " FROM vehicle_realtime_position p" + TBOX_JOIN
                    + " WHERE " + acl.where() + ") x";
            Map<String, Object> sum = analyticsJdbcTemplate.queryForMap(sumSql, acl.args());

            // coverage: distinct province / city counts
            String covSql = "SELECT COUNT(DISTINCT NULLIF(p.province_name, '')) AS provinces,"
                    + " COUNT(DISTINCT NULLIF(p.city_name, '')) AS cities"
                    + " FROM vehicle_realtime_position p WHERE " + acl.where();
            Map<String, Object> cov = analyticsJdbcTemplate.queryForMap(covSql, acl.args());

            // region top5
            String regSql = "SELECT COALESCE(NULLIF(p.province_name, ''), 'N/A') AS name, COUNT(*) AS total,"
                    + " SUM(CASE WHEN" + LEVEL_EXPR + " = 0 THEN 1 ELSE 0 END) AS online"
                    + " FROM vehicle_realtime_position p" + TBOX_JOIN
                    + " WHERE " + acl.where()
                    + " GROUP BY COALESCE(NULLIF(p.province_name, ''), 'N/A') ORDER BY total DESC LIMIT 5";
            List<Map<String, Object>> regionTop = analyticsJdbcTemplate.queryForList(regSql, acl.args());

            // brand top10
            String brandSql = "SELECT COALESCE(NULLIF(p.car_brand_name, ''), 'N/A') AS name, COUNT(*) AS cnt"
                    + " FROM vehicle_realtime_position p WHERE " + acl.where()
                    + " GROUP BY COALESCE(NULLIF(p.car_brand_name, ''), 'N/A') ORDER BY cnt DESC LIMIT 10";
            List<Map<String, Object>> brandTop = analyticsJdbcTemplate.queryForList(brandSql, acl.args());

            // alerts aggregate: last 24h total + severe (risk_level >= 2, display-only assumption)
            List<Object> aargs = new java.util.ArrayList<>();
            String awhere = alertAcl(fctx, orgs, aargs);
            String alertSql = "SELECT COUNT(*) AS total,"
                    + " COALESCE(SUM(CASE WHEN a.risk_level >= 2 THEN 1 ELSE 0 END), 0) AS severe"
                    + " FROM adas_aggregation_simple_alarm_record a"
                    + " WHERE " + awhere
                    + " AND a.alarm_start_time >= DATE_SUB(NOW(), INTERVAL 24 HOUR)"
                    + " AND a.alarm_start_time < DATE_ADD(NOW(), INTERVAL 10 MINUTE)";
            Map<String, Object> alertAgg = analyticsJdbcTemplate.queryForMap(alertSql, aargs.toArray());

            // alert trend by hour (last 24h)
            List<Object> targs = new java.util.ArrayList<>();
            String twhere = alertAcl(fctx, orgs, targs);
            String trendSql = "SELECT HOUR(a.alarm_start_time) AS hh, COUNT(*) AS cnt"
                    + " FROM adas_aggregation_simple_alarm_record a"
                    + " WHERE " + twhere
                    + " AND a.alarm_start_time >= DATE_SUB(NOW(), INTERVAL 24 HOUR)"
                    + " AND a.alarm_start_time < DATE_ADD(NOW(), INTERVAL 10 MINUTE)"
                    + " GROUP BY HOUR(a.alarm_start_time) ORDER BY hh";
            List<Map<String, Object>> trend = analyticsJdbcTemplate.queryForList(trendSql, targs.toArray());

            out.put("totalVehicles", sum.get("total"));
            out.put("onlineVehicles", sum.get("run"));
            out.put("parkVehicles", sum.get("park"));
            out.put("lostVehicles", sum.get("lost"));
            out.put("mileageSum", sum.get("mileage_sum"));
            out.put("coverageProvince", cov.get("provinces"));
            out.put("coverageCity", cov.get("cities"));
            out.put("regionTop", regionTop);
            out.put("brandTop", brandTop);
            out.put("alertTotal", alertAgg.get("total"));
            out.put("alertSevere", alertAgg.get("severe"));
            out.put("alertTrend", trend);
            return out;
        });
    }
}
