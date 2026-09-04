package com.dst.v2xagent.copilot;

import com.dst.v2xagent.capability.FleetMappingService;
import com.dst.v2xagent.capability.model.CapabilityDefinition;
import com.dst.v2xagent.capability.model.TableResult;
import com.dst.v2xagent.common.PermissionContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 真实库查询层（dst_v2x_db 114 表生产数据）。
 * SQL 映射复用自已验证过的 capability yaml；告警码、等级、空值在 Java 侧映射，
 * SQL 里不嵌任何字符串常量。红线：LLM 不见 SQL，ACL 强制拼入。
 */
@Component
public class RealQueries {

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String MIN_VALID_TIME = "2020-01-01 00:00:00";

    private final JdbcTemplate jdbc;
    private final FleetMappingService fleetMapping;
    private volatile LocalDate baseDate;
    private volatile long baseDateLoadedAt;

    public RealQueries(@Qualifier("analyticsJdbcTemplate") JdbcTemplate jdbc,
                       FleetMappingService fleetMapping) {
        this.jdbc = jdbc;
        this.fleetMapping = fleetMapping;
    }

    /** 数据基准日：里程表最大数据日期（cmd=2） */
    public LocalDate baseDate() {
        if (baseDate != null && System.currentTimeMillis() - baseDateLoadedAt < 300_000) {
            return baseDate;
        }
        LocalDate d = jdbc.queryForObject(
                "SELECT MAX(DATE(data_time)) FROM vehicle_mileage_data WHERE cmd = 2 AND data_time > ?",
                LocalDate.class, MIN_VALID_TIME);
        baseDate = d != null ? d : LocalDate.now();
        baseDateLoadedAt = System.currentTimeMillis();
        return baseDate;
    }

    /** 可见组织：admin 返回 null（不加过滤）；其他用户返回 org 集合（空集 = fail closed） */
    private Set<String> aclOrgs(PermissionContext ctx) {
        if (ctx.isAdmin()) {
            return null;
        }
        return fleetMapping.expandOrgNames(ctx);
    }

    /** 拼 ACL 过滤片段：null=不过滤，空集=不可见任何数据 */
    private String aclFragment(Set<String> orgs, String orgColumn, List<Object> params) {
        if (orgs == null) {
            return " ";
        }
        if (orgs.isEmpty()) {
            return " AND 1 = 0 ";
        }
        params.addAll(orgs);
        return " AND " + orgColumn + " IN (" + placeholders(orgs.size()) + ") ";
    }

    /** 无 org 列的表用 vin 子查询拼 ACL */
    private String aclFragmentVin(Set<String> orgs, String vinColumn, List<Object> params) {
        if (orgs == null) {
            return " ";
        }
        if (orgs.isEmpty()) {
            return " AND 1 = 0 ";
        }
        params.addAll(orgs);
        return " AND " + vinColumn + " IN (SELECT vin_code FROM basic_vehicle_info WHERE org_name IN ("
                + placeholders(orgs.size()) + ")) ";
    }

    /** 车辆模糊匹配（车牌或 VIN） */
    public List<Map<String, Object>> resolveVehicles(String vehicle, PermissionContext ctx) {
        if (vehicle == null || vehicle.isBlank()) {
            return List.of();
        }
        List<Object> params = new ArrayList<>();
        String like = "%" + vehicle.trim() + "%";
        params.add(like);
        params.add(like);
        String sql = "SELECT b.vin_code AS vin, b.car_no AS plate_no, b.org_name AS fleet_name, "
                + " b.car_brand_name AS brand, b.car_model_name AS model "
                + " FROM basic_vehicle_info b "
                + " WHERE b.del_flag = 0 AND (b.car_no LIKE ? OR b.vin_code LIKE ?) "
                + aclFragment(aclOrgs(ctx), "b.org_name", params)
                + " LIMIT 10";
        List<Map<String, Object>> rows = jdbc.queryForList(sql, params.toArray());
        System.out.println("[resolveVehicles] input=" + escapeCodepoints(vehicle)
                + " like=" + escapeCodepoints(like) + " rows=" + rows.size());
        return rows;
    }

    /** 排障用：字符串码点转义（中文/隐藏字符可见） */
    private static String escapeCodepoints(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 128) {
                sb.append(c);
            } else {
                sb.append(String.format("\\u%04x", (int) c));
            }
        }
        return sb.toString();
    }

    /** 车辆档案 */
    public SimQueries.Outcome vehicleInfo(String vin, PermissionContext ctx) {
        CapabilityDefinition def = def("real_vehicle_info", "车辆档案", "table",
                col("plate_no", "id", "车牌号", null),
                col("vin", "id", "VIN", null),
                col("fleet_name", "category", "所属组织", null),
                col("brand", "category", "品牌", null),
                col("model", "category", "车型", null),
                col("vehicle_type", "category", "车辆类型", null),
                col("customer_name", "category", "客户", null),
                col("motor_num", "category", "电机号", null));
        List<Object> params = new ArrayList<>();
        params.add(vin);
        String sql = "SELECT b.car_no, b.vin_code, b.org_name, b.car_brand_name, b.car_model_name, "
                + " b.car_type, b.customer_name, b.motor_num "
                + " FROM basic_vehicle_info b WHERE b.vin_code = ? "
                + aclFragment(aclOrgs(ctx), "b.org_name", params);
        return new SimQueries.Outcome(def, exec(def, sql, params));
    }

    /** 离线超 N 小时车辆清单（快照表最后上报时间） */
    public SimQueries.Outcome offlineVehicles(int hours, PermissionContext ctx) {
        CapabilityDefinition def = def("real_offline_list", "离线车辆清单", "table",
                col("plate_no", "id", "车牌号", null),
                col("fleet_name", "category", "组织", null),
                col("last_report_time", "time", "最后上报", null),
                col("offline_hours", "metric", "离线时长", "小时"));
        List<Object> params = new ArrayList<>();
        String sql = "SELECT COALESCE(b.car_no, t.vin_code) AS plate_no, b.org_name AS fleet_name, "
                + " t.dev_time AS last_report_time, "
                + " ROUND(TIMESTAMPDIFF(MINUTE, t.dev_time, NOW()) / 60, 1) AS offline_hours "
                + " FROM tbox_realtime_vehicle_data t "
                + " LEFT JOIN basic_vehicle_info b ON t.vin_code = b.vin_code "
                + " WHERE t.dev_time < DATE_SUB(NOW(), INTERVAL " + hours + " HOUR) "
                + aclFragment(aclOrgs(ctx), "b.org_name", params)
                + " ORDER BY t.dev_time ASC LIMIT 200";
        return new SimQueries.Outcome(def, exec(def, sql, params));
    }

    /** 车辆最后位置（report_time 异常值已过滤） */
    public SimQueries.Outcome vehicleLocation(String vin, PermissionContext ctx) {
        CapabilityDefinition def = def("real_last_location", "最后位置", "table",
                col("plate_no", "id", "车牌号", null),
                col("report_time", "time", "上报时间", null),
                col("lng", "geo_lng", "经度", null),
                col("lat", "geo_lat", "纬度", null),
                col("province_name", "category", "省份", null),
                col("city_name", "category", "城市", null),
                col("district_name", "category", "区县", null),
                col("mileage", "metric", "里程表", "km"));
        List<Object> params = new ArrayList<>();
        params.add(vin);
        params.add(MIN_VALID_TIME);
        String sql = "SELECT p.car_no, p.report_time, "
                + " ROUND(p.gcj02_lng, 6) AS lng, ROUND(p.gcj02_lat, 6) AS lat, "
                + " p.province_name, p.city_name, p.district_name, p.mileage "
                + " FROM vehicle_realtime_position p "
                + " WHERE p.vin_code = ? "
                + " AND p.report_time > ? AND p.report_time < DATE_ADD(NOW(), INTERVAL 1 DAY) "
                + aclFragmentVin(aclOrgs(ctx), "p.vin_code", params)
                + " ORDER BY p.report_time DESC LIMIT 1";
        return new SimQueries.Outcome(def, exec(def, sql, params, row -> {
            if (row[0] == null || String.valueOf(row[0]).isBlank()) {
                row[0] = vin;
            }
            return row;
        }));
    }

    /** 按类型统计告警次数（类型码 Java 侧映射名称） */
    public SimQueries.Outcome alarmsByType(TimeRanges.Range range, String vin, PermissionContext ctx) {
        CapabilityDefinition def = def("real_alarm_count", "告警类型统计", "bar",
                col("alarm_name", "category", "告警类型", null),
                col("alarm_count", "metric", "告警次数", "次"));
        List<Object> params = new ArrayList<>();
        params.add(range.from().format(DT));
        params.add(range.to().format(DT));
        String sql = "SELECT a.alarm_type, COUNT(1) AS alarm_count "
                + " FROM adas_aggregation_simple_alarm_record a "
                + " WHERE a.alarm_start_time >= ? AND a.alarm_start_time < ? "
                + vinClause(vin, "a.car_vin", params)
                + aclFragmentVin(aclOrgs(ctx), "a.car_vin", params)
                + " GROUP BY a.alarm_type ORDER BY alarm_count DESC LIMIT 50";
        return new SimQueries.Outcome(def, exec(def, sql, params, row -> {
            row[0] = alarmName(row[0]);
            return row;
        }));
    }

    /** 告警明细 */
    public SimQueries.Outcome alarmList(TimeRanges.Range range, String vin, int limit, PermissionContext ctx) {
        CapabilityDefinition def = def("real_alarm_list", "告警明细", "table",
                col("alarm_start_time", "time", "告警时间", null),
                col("plate_no", "id", "车牌号", null),
                col("alarm_name", "category", "告警类型", null),
                col("risk_level", "category", "风险等级", null),
                col("alarm_end_time", "time", "结束时间", null));
        int top = Math.min(Math.max(limit, 1), 200);
        List<Object> params = new ArrayList<>();
        String sql = "SELECT a.alarm_start_time, COALESCE(b.car_no, a.car_vin) AS plate_no, "
                + " a.alarm_type, a.risk_level, a.alarm_end_time "
                + " FROM adas_aggregation_simple_alarm_record a "
                + " LEFT JOIN basic_vehicle_info b ON a.car_vin = b.vin_code "
                + " WHERE a.alarm_start_time >= ? AND a.alarm_start_time < ? "
                + vinClause(vin, "a.car_vin", params)
                + aclFragmentVin(aclOrgs(ctx), "a.car_vin", params)
                + " ORDER BY a.alarm_start_time DESC LIMIT " + top;
        params.add(0, range.from().format(DT));
        params.add(1, range.to().format(DT));
        return new SimQueries.Outcome(def, exec(def, sql, params, row -> {
            row[2] = alarmName(row[2]);
            row[3] = riskText(row[3]);
            return row;
        }));
    }

    /** 车队告警对比：总量 + 严重数 + 涉及车数 */
    public SimQueries.Outcome fleetAlarmCompare(TimeRanges.Range range, PermissionContext ctx) {
        CapabilityDefinition def = def("real_fleet_alarm", "车队告警对比", "bar",
                col("fleet_name", "category", "组织", null),
                col("alarm_count", "metric", "告警总数", "次"),
                col("severe_count", "metric", "严重告警", "次"),
                col("vehicle_cnt", "metric", "涉及车辆", "台"));
        List<Object> params = new ArrayList<>();
        params.add(range.from().format(DT));
        params.add(range.to().format(DT));
        String sql = "SELECT b.org_name, COUNT(1) AS alarm_count, "
                + " SUM(CASE WHEN a.risk_level >= 2 THEN 1 ELSE 0 END) AS severe_count, "
                + " COUNT(DISTINCT a.car_vin) AS vehicle_cnt "
                + " FROM adas_aggregation_simple_alarm_record a "
                + " JOIN basic_vehicle_info b ON a.car_vin = b.vin_code "
                + " WHERE a.alarm_start_time >= ? AND a.alarm_start_time < ? "
                + aclFragment(aclOrgs(ctx), "b.org_name", params)
                + " GROUP BY b.org_name ORDER BY alarm_count DESC LIMIT 50";
        return new SimQueries.Outcome(def, exec(def, sql, params));
    }

    /** 按部位统计故障 */
    public SimQueries.Outcome faultsByPart(TimeRanges.Range range, String vin, PermissionContext ctx) {
        CapabilityDefinition def = def("real_fault_count", "故障部位统计", "bar",
                col("fault_part", "category", "故障部位", null),
                col("fault_count", "metric", "故障次数", "次"));
        List<Object> params = new ArrayList<>();
        params.add(range.from().format(DT));
        params.add(range.to().format(DT));
        String sql = "SELECT f.fault_part, COUNT(1) AS fault_count "
                + " FROM tbox_fault_history f "
                + " WHERE f.data_time >= ? AND f.data_time < ? "
                + vinClause(vin, "f.vin_code", params)
                + aclFragmentVin(aclOrgs(ctx), "f.vin_code", params)
                + " GROUP BY f.fault_part ORDER BY fault_count DESC LIMIT 50";
        return new SimQueries.Outcome(def, exec(def, sql, params, row -> {
            if (row[0] == null || String.valueOf(row[0]).isBlank()) {
                row[0] = "未标注";
            }
            return row;
        }));
    }

    /** 故障明细（真实库无恢复时间概念，activeOnly 仅保留接口不过滤） */
    public SimQueries.Outcome faultList(TimeRanges.Range range, String vin, boolean activeOnly, PermissionContext ctx) {
        CapabilityDefinition def = def("real_fault_list", "故障明细", "table",
                col("data_time", "time", "上报时间", null),
                col("plate_no", "id", "车牌号", null),
                col("fault_name", "category", "故障名称", null),
                col("fault_part", "category", "部位", null),
                col("fault_level", "category", "等级", null),
                col("fault_reason", "category", "原因", null));
        List<Object> params = new ArrayList<>();
        String sql = "SELECT f.data_time, COALESCE(f.car_no, f.vin_code) AS plate_no, "
                + " f.fault_name, f.fault_part, f.fault_level, f.fault_reason "
                + " FROM tbox_fault_history f "
                + " WHERE f.data_time >= ? AND f.data_time < ? "
                + vinClause(vin, "f.vin_code", params)
                + aclFragmentVin(aclOrgs(ctx), "f.vin_code", params)
                + " ORDER BY f.data_time DESC LIMIT 200";
        params.add(0, range.from().format(DT));
        params.add(1, range.to().format(DT));
        return new SimQueries.Outcome(def, exec(def, sql, params, row -> {
            if (row[3] == null || String.valueOf(row[3]).isBlank()) {
                row[3] = "未标注";
            }
            return row;
        }));
    }

    /** 车队里程对比 */
    public SimQueries.Outcome mileageByFleet(TimeRanges.Range range, PermissionContext ctx) {
        CapabilityDefinition def = def("real_fleet_mileage", "车队里程对比", "bar",
                col("fleet_name", "category", "组织", null),
                col("total_km", "metric", "总里程", "km"),
                col("vehicle_count", "metric", "活跃车辆", "台"),
                col("avg_daily_km", "metric", "车均日里程", "km"));
        long days = Math.max(1, java.time.Duration.between(range.from(), range.to()).toDays());
        List<Object> params = new ArrayList<>();
        String sql = "SELECT x.org_name, ROUND(SUM(x.diff), 1) AS total_km, COUNT(1) AS vehicle_count, "
                + " ROUND(SUM(x.diff) / (COUNT(1) * " + days + "), 1) AS avg_daily_km "
                + " FROM ( "
                + "   SELECT m.vin, b.org_name, MAX(m.mileage_total) - MIN(m.mileage_total) AS diff "
                + "   FROM vehicle_mileage_data m JOIN basic_vehicle_info b ON m.vin = b.vin_code "
                + "   WHERE m.data_time >= ? AND m.data_time < ? AND m.cmd = 2 "
                + aclFragment(aclOrgs(ctx), "b.org_name", params)
                + "   GROUP BY m.vin, b.org_name HAVING diff >= 0 AND diff < 100000 ) x "
                + " GROUP BY x.org_name ORDER BY total_km DESC LIMIT 50";
        params.add(0, range.from().format(DT));
        params.add(1, range.to().format(DT));
        return new SimQueries.Outcome(def, exec(def, sql, params));
    }

    /** 每日里程趋势（可指定单车；否则为可见范围合计） */
    public SimQueries.Outcome mileageDaily(TimeRanges.Range range, String vin, PermissionContext ctx) {
        CapabilityDefinition def = def("real_mileage_daily", "每日里程趋势", "line",
                col("dt", "time", "日期", null),
                col("mileage_km", "metric", "里程", "km"),
                col("vehicle_cnt", "metric", "活跃车辆", "台"));
        List<Object> params = new ArrayList<>();
        String sql = "SELECT x.stat_date, ROUND(SUM(x.daily_mileage), 1) AS mileage_km, COUNT(1) AS vehicle_cnt "
                + " FROM ( "
                + "   SELECT DATE(m.data_time) AS stat_date, m.vin, "
                + "   MAX(m.mileage_total) - MIN(m.mileage_total) AS daily_mileage "
                + "   FROM vehicle_mileage_data m "
                + "   WHERE m.data_time >= ? AND m.data_time < ? AND m.cmd = 2 "
                + vinClause(vin, "m.vin", params)
                + aclFragmentVin(aclOrgs(ctx), "m.vin", params)
                + "   GROUP BY DATE(m.data_time), m.vin "
                + "   HAVING daily_mileage >= 0 AND daily_mileage < 2000 ) x "
                + " GROUP BY x.stat_date ORDER BY x.stat_date LIMIT 120";
        params.add(0, range.from().format(DT));
        params.add(1, range.to().format(DT));
        return new SimQueries.Outcome(def, exec(def, sql, params));
    }

    /** 充电统计：次数 / 电量 / 时长 */
    public SimQueries.Outcome chargeStats(TimeRanges.Range range, String vin, PermissionContext ctx) {
        CapabilityDefinition def = def("real_charge_stats", "充电统计", "bar",
                col("dt", "time", "日期", null),
                col("charge_times", "metric", "充电次数", "次"),
                col("charge_kwh", "metric", "充电量", "kWh"),
                col("charge_hours", "metric", "充电时长", "h"));
        List<Object> params = new ArrayList<>();
        String sql = "SELECT t.query_date, SUM(t.charge_times) AS charge_times, "
                + " ROUND(SUM(t.charge_total), 1) AS charge_kwh, "
                + " ROUND(SUM(t.charge_duration) / 60, 1) AS charge_hours "
                + " FROM tbox_charge_day t "
                + " WHERE t.query_date >= ? AND t.query_date < ? "
                + vinClause(vin, "t.vin_code", params)
                + aclFragmentVin(aclOrgs(ctx), "t.vin_code", params)
                + " GROUP BY t.query_date ORDER BY t.query_date LIMIT 120";
        params.add(0, range.from().format(DT));
        params.add(1, range.to().format(DT));
        return new SimQueries.Outcome(def, exec(def, sql, params));
    }

    // ---------- 内部工具 ----------

    private static String vinClause(String vin, String vinColumn, List<Object> params) {
        if (vin == null) {
            return " ";
        }
        params.add(vin);
        return " AND " + vinColumn + " = ? ";
    }

    private static String placeholders(int n) {
        return String.join(", ", java.util.Collections.nCopies(Math.max(n, 1), "?"));
    }

    /** JT/T 1078 告警码 -> 名称（1xxx）；其他码显示原码 */
    static String alarmName(Object code) {
        if (code == null) {
            return "未知告警";
        }
        return switch (Integer.parseInt(String.valueOf(code))) {
            case 1001 -> "前向碰撞报警";
            case 1002 -> "车道偏离报警";
            case 1003 -> "车距过近报警";
            case 1004 -> "行人碰撞报警";
            case 1005 -> "超速报警";
            case 1006 -> "疲劳驾驶报警";
            case 1007 -> "接打电话报警";
            case 1008 -> "抽烟报警";
            case 1009 -> "分神驾驶报警";
            case 1010 -> "驾驶员异常报警";
            case 1011 -> "双手脱离方向盘";
            default -> "告警类型 " + code;
        };
    }

    private static String riskText(Object level) {
        if (level == null) {
            return "-";
        }
        return "L" + level;
    }

    private static CapabilityDefinition.ColumnDef col(String name, String semantic, String display, String unit) {
        CapabilityDefinition.ColumnDef c = new CapabilityDefinition.ColumnDef();
        c.setName(name);
        c.setSemantic(semantic);
        c.setDisplay(display);
        c.setUnit(unit);
        return c;
    }

    private static CapabilityDefinition def(String id, String display, String chartHint,
                                            CapabilityDefinition.ColumnDef... cols) {
        CapabilityDefinition d = new CapabilityDefinition();
        d.setId(id);
        d.setDisplay(display);
        d.setChartHint(chartHint);
        CapabilityDefinition.ReturnsDef r = new CapabilityDefinition.ReturnsDef();
        r.setShape("table");
        r.setColumns(List.of(cols));
        d.setReturns(r);
        return d;
    }

    private TableResult exec(CapabilityDefinition def, String sql, List<Object> params) {
        return exec(def, sql, params, null);
    }

    private TableResult exec(CapabilityDefinition def, String sql, List<Object> params,
                             Function<Object[], Object[]> mapper) {
        long start = System.currentTimeMillis();
        int width = def.getReturns().getColumns().size();
        List<Map<String, Object>> raw = jdbc.queryForList(sql, params.toArray());
        List<Object[]> rows = new ArrayList<>(raw.size());
        for (Map<String, Object> r : raw) {
            Object[] row = new Object[width];
            int i = 0;
            for (Object v : r.values()) {
                if (i < width) {
                    row[i] = v;
                }
                i++;
            }
            rows.add(mapper == null ? row : mapper.apply(row));
        }
        long elapsed = System.currentTimeMillis() - start;
        String snapshot = sql.length() > 300 ? sql.substring(0, 300) + "..." : sql;
        return new TableResult(def.getReturns().getColumns(), rows, rows.size(), false,
                new TableResult.Freshness(baseDate() + " 23:59", "t_plus_1", 0, null),
                new TableResult.ExecStats(elapsed, rows.size(), snapshot));
    }
}
