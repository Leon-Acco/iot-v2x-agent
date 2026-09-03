package com.dst.v2xagent.copilot;

import com.dst.v2xagent.capability.model.CapabilityDefinition;
import com.dst.v2xagent.capability.model.TableResult;
import com.dst.v2xagent.common.PermissionContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 模拟库查询层：所有 SQL 都是确定性模板 + PreparedStatement 参数绑定。
 * 红线：LLM 永远看不到 SQL；车队 ACL 在本层强制拼入，模型无法绕过。
 */
@Component
public class SimQueries {

    private static final String SIM = "dst_v2x_sim";
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbc;
    /** 数据基准日缓存（5 分钟） */
    private volatile LocalDate baseDate;
    private volatile long baseDateLoadedAt;

    public SimQueries(@Qualifier("analyticsJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 一次查询的完整产物：列定义 + 结果表 */
    public record Outcome(CapabilityDefinition def, TableResult table) {}

    /** 数据基准日：库内最大里程日期，相对时间范围一律锡定到它 */
    public LocalDate baseDate() {
        if (baseDate != null && System.currentTimeMillis() - baseDateLoadedAt < 300_000) {
            return baseDate;
        }
        LocalDate d = jdbc.queryForObject("SELECT MAX(dt) FROM " + SIM + ".fact_mileage_daily", LocalDate.class);
        baseDate = d != null ? d : LocalDate.now();
        baseDateLoadedAt = System.currentTimeMillis();
        return baseDate;
    }

    /** 当前用户可见车队：admin 全量；其他用户取账号绑定车队与库内车队的交集 */
    public List<String> aclFleets(PermissionContext ctx) {
        List<String> all = jdbc.queryForList("SELECT fleet_id FROM " + SIM + ".dim_fleet", String.class);
        if (ctx.isAdmin()) {
            return all;
        }
        List<String> allowed = new ArrayList<>();
        for (String f : all) {
            if (ctx.fleetIds() != null && ctx.fleetIds().contains(f)) {
                allowed.add(f);
            }
        }
        return allowed;
    }

    /** 车辆模糊匹配（车牌或 VIN），返回候选列表供澄清或直接命中 */
    public List<Map<String, Object>> resolveVehicles(String vehicle, PermissionContext ctx) {
        List<String> fleets = aclFleets(ctx);
        if (fleets.isEmpty() || vehicle == null || vehicle.isBlank()) {
            return List.of();
        }
        String sql = "SELECT v.vin, v.plate_no, v.fleet_id, f.fleet_name, v.brand, v.model "
                + " FROM " + SIM + ".dim_vehicle v JOIN " + SIM + ".dim_fleet f ON v.fleet_id = f.fleet_id "
                + " WHERE v.fleet_id IN (" + placeholders(fleets.size()) + ") "
                + " AND (v.plate_no LIKE ? OR v.vin LIKE ?) LIMIT 10";
        List<Object> params = new ArrayList<>(fleets);
        String like = "%" + vehicle.trim() + "%";
        params.add(like);
        params.add(like);
        return jdbc.queryForList(sql, params.toArray());
    }

    /** 车辆档案 */
    public Outcome vehicleInfo(String vin, PermissionContext ctx) {
        CapabilityDefinition def = def("sim_vehicle_info", "车辆档案", "table",
                col("plate_no", "id", "车牌号", null),
                col("vin", "id", "VIN", null),
                col("fleet_name", "category", "所属车队", null),
                col("brand", "category", "品牌", null),
                col("model", "category", "车型", null),
                col("vehicle_type", "category", "类型", null),
                col("battery_capacity_kwh", "metric", "电池容量", "kWh"),
                col("rated_range_km", "metric", "标称续航", "km"),
                col("manufacture_year", "category", "生产年份", null),
                col("status_text", "category", "状态", null));
        List<String> fleets = aclFleets(ctx);
        String sql = "SELECT v.plate_no, v.vin, f.fleet_name, v.brand, v.model, v.vehicle_type, "
                + " v.battery_capacity_kwh, v.rated_range_km, v.manufacture_year, v.status "
                + " FROM " + SIM + ".dim_vehicle v JOIN " + SIM + ".dim_fleet f ON v.fleet_id = f.fleet_id "
                + " WHERE v.vin = ? AND v.fleet_id IN (" + placeholders(fleets.size()) + ") ";
        List<Object> params = new ArrayList<>();
        params.add(vin);
        params.addAll(fleets);
        return new Outcome(def, exec(def, sql, params, row -> {
            row[9] = "1".equals(String.valueOf(row[9])) ? "运营中" : "停用";
            return row;
        }));
    }

    /** 离线超 N 小时车辆清单 */
    public Outcome offlineVehicles(int hours, PermissionContext ctx) {
        CapabilityDefinition def = def("sim_offline_list", "离线车辆清单", "table",
                col("plate_no", "id", "车牌号", null),
                col("fleet_name", "category", "车队", null),
                col("last_online_time", "time", "最后上线时间", null),
                col("offline_hours", "metric", "离线时长", "小时"),
                col("city", "category", "城市", null));
        List<String> fleets = aclFleets(ctx);
        String sql = "SELECT t.plate_no, t.fleet_name, t.last_online_time, t.offline_hours, t.city FROM ( "
                + " SELECT v.plate_no, f.fleet_name, MAX(s.last_online_time) AS last_online_time, "
                + " ROUND(TIMESTAMPDIFF(HOUR, MAX(s.last_online_time), ?), 1) AS offline_hours, "
                + " MAX(s.city) AS city "
                + " FROM " + SIM + ".dim_vehicle v "
                + " JOIN " + SIM + ".dim_fleet f ON v.fleet_id = f.fleet_id "
                + " LEFT JOIN " + SIM + ".fact_vehicle_status_daily s ON v.vin = s.vin "
                + " WHERE v.fleet_id IN (" + placeholders(fleets.size()) + ") "
                + " GROUP BY v.plate_no, f.fleet_name ) t "
                + " WHERE t.last_online_time IS NULL OR t.offline_hours >= ? "
                + " ORDER BY t.offline_hours DESC LIMIT 100";
        List<Object> params = new ArrayList<>();
        params.add(LocalDateTime.now().format(DT));
        params.addAll(fleets);
        params.add(hours);
        return new Outcome(def, exec(def, sql, params));
    }

    /** 车辆最后位置 */
    public Outcome vehicleLocation(String vin, PermissionContext ctx) {
        CapabilityDefinition def = def("sim_last_location", "最后位置", "table",
                col("plate_no", "id", "车牌号", null),
                col("last_online_time", "time", "上报时间", null),
                col("last_lng", "geo_lng", "经度", null),
                col("last_lat", "geo_lat", "纬度", null),
                col("city", "category", "城市", null),
                col("district", "category", "区县", null),
                col("odometer_km", "metric", "里程表", "km"));
        List<String> fleets = aclFleets(ctx);
        String sql = "SELECT v.plate_no, s.last_online_time, s.last_lng, s.last_lat, s.city, s.district, s.odometer_km "
                + " FROM " + SIM + ".fact_vehicle_status_daily s "
                + " JOIN " + SIM + ".dim_vehicle v ON s.vin = v.vin "
                + " WHERE s.vin = ? AND v.fleet_id IN (" + placeholders(fleets.size()) + ") "
                + " ORDER BY s.dt DESC, s.last_online_time DESC LIMIT 1";
        List<Object> params = new ArrayList<>();
        params.add(vin);
        params.addAll(fleets);
        return new Outcome(def, exec(def, sql, params));
    }

    // ---------- 告警域 ----------

    /** 按类型统计告警次数 */
    public Outcome alarmsByType(TimeRanges.Range range, String vin, PermissionContext ctx) {
        CapabilityDefinition def = def("sim_alarm_count", "告警类型统计", "bar",
                col("alarm_name", "category", "告警类型", null),
                col("alarm_count", "metric", "告警次数", "次"));
        List<String> fleets = aclFleets(ctx);
        String sql = "SELECT a.alarm_name, COUNT(1) AS alarm_count "
                + " FROM " + SIM + ".fact_alarm a JOIN " + SIM + ".dim_vehicle v ON a.vin = v.vin "
                + " WHERE a.start_time >= ? AND a.start_time < ? "
                + " AND v.fleet_id IN (" + placeholders(fleets.size()) + ") "
                + vinClause(vin)
                + " GROUP BY a.alarm_name ORDER BY alarm_count DESC LIMIT 50";
        List<Object> params = new ArrayList<>();
        params.add(range.from().format(DT));
        params.add(range.to().format(DT));
        params.addAll(fleets);
        addVin(params, vin);
        return new Outcome(def, exec(def, sql, params));
    }

    /** 告警明细 */
    public Outcome alarmList(TimeRanges.Range range, String vin, int limit, PermissionContext ctx) {
        CapabilityDefinition def = def("sim_alarm_list", "告警明细", "table",
                col("start_time", "time", "告警时间", null),
                col("plate_no", "id", "车牌号", null),
                col("alarm_name", "category", "告警类型", null),
                col("alarm_level", "category", "等级", null),
                col("city", "category", "城市", null),
                col("speed_kmh", "metric", "车速", "km/h"));
        List<String> fleets = aclFleets(ctx);
        int top = Math.min(Math.max(limit, 1), 200);
        String sql = "SELECT a.start_time, v.plate_no, a.alarm_name, a.alarm_level, a.city, a.speed_kmh "
                + " FROM " + SIM + ".fact_alarm a JOIN " + SIM + ".dim_vehicle v ON a.vin = v.vin "
                + " WHERE a.start_time >= ? AND a.start_time < ? "
                + " AND v.fleet_id IN (" + placeholders(fleets.size()) + ") "
                + vinClause(vin)
                + " ORDER BY a.start_time DESC LIMIT " + top;
        List<Object> params = new ArrayList<>();
        params.add(range.from().format(DT));
        params.add(range.to().format(DT));
        params.addAll(fleets);
        addVin(params, vin);
        return new Outcome(def, exec(def, sql, params));
    }

    /** 车队告警对比：总量 + 严重告警量 + 百车日均 */
    public Outcome fleetAlarmCompare(TimeRanges.Range range, PermissionContext ctx) {
        CapabilityDefinition def = def("sim_fleet_alarm", "车队告警对比", "bar",
                col("fleet_name", "category", "车队", null),
                col("alarm_count", "metric", "告警总数", "次"),
                col("severe_count", "metric", "严重告警", "次"),
                col("per_vehicle_day", "metric", "百车日均", "次"));
        List<String> fleets = aclFleets(ctx);
        long days = Math.max(1, java.time.Duration.between(range.from(), range.to()).toDays());
        String sql = "SELECT f.fleet_name, COUNT(1) AS alarm_count, "
                + " SUM(CASE WHEN a.alarm_level >= 3 THEN 1 ELSE 0 END) AS severe_count, "
                + " ROUND(COUNT(1) * 100.0 / (f.vehicle_count * " + days + "), 1) AS per_vehicle_day "
                + " FROM " + SIM + ".fact_alarm a "
                + " JOIN " + SIM + ".dim_vehicle v ON a.vin = v.vin "
                + " JOIN " + SIM + ".dim_fleet f ON v.fleet_id = f.fleet_id "
                + " WHERE a.start_time >= ? AND a.start_time < ? "
                + " AND v.fleet_id IN (" + placeholders(fleets.size()) + ") "
                + " GROUP BY f.fleet_name, f.vehicle_count ORDER BY alarm_count DESC";
        List<Object> params = new ArrayList<>();
        params.add(range.from().format(DT));
        params.add(range.to().format(DT));
        params.addAll(fleets);
        return new Outcome(def, exec(def, sql, params));
    }

    // ---------- 故障域 ----------

    /** 按部位统计故障 */
    public Outcome faultsByPart(TimeRanges.Range range, String vin, PermissionContext ctx) {
        CapabilityDefinition def = def("sim_fault_count", "故障部位统计", "bar",
                col("fault_part", "category", "故障部位", null),
                col("fault_count", "metric", "故障次数", "次"),
                col("active_count", "metric", "未恢复", "次"));
        List<String> fleets = aclFleets(ctx);
        String sql = "SELECT x.fault_part, COUNT(1) AS fault_count, "
                + " SUM(CASE WHEN x.recover_time IS NULL THEN 1 ELSE 0 END) AS active_count "
                + " FROM " + SIM + ".fact_fault x JOIN " + SIM + ".dim_vehicle v ON x.vin = v.vin "
                + " WHERE x.report_time >= ? AND x.report_time < ? "
                + " AND v.fleet_id IN (" + placeholders(fleets.size()) + ") "
                + vinClauseX(vin)
                + " GROUP BY x.fault_part ORDER BY fault_count DESC LIMIT 50";
        List<Object> params = new ArrayList<>();
        params.add(range.from().format(DT));
        params.add(range.to().format(DT));
        params.addAll(fleets);
        addVin(params, vin);
        return new Outcome(def, exec(def, sql, params));
    }

    /** 故障明细（可只看未恢复） */
    public Outcome faultList(TimeRanges.Range range, String vin, boolean activeOnly, PermissionContext ctx) {
        CapabilityDefinition def = def("sim_fault_list", "故障明细", "table",
                col("report_time", "time", "上报时间", null),
                col("plate_no", "id", "车牌号", null),
                col("fault_name", "category", "故障名称", null),
                col("fault_part", "category", "部位", null),
                col("fault_level", "category", "等级", null),
                col("recover_time", "time", "恢复时间", null));
        List<String> fleets = aclFleets(ctx);
        String active = activeOnly ? " AND x.recover_time IS NULL " : " ";
        String sql = "SELECT x.report_time, v.plate_no, x.fault_name, x.fault_part, x.fault_level, x.recover_time "
                + " FROM " + SIM + ".fact_fault x JOIN " + SIM + ".dim_vehicle v ON x.vin = v.vin "
                + " WHERE x.report_time >= ? AND x.report_time < ? " + active
                + " AND v.fleet_id IN (" + placeholders(fleets.size()) + ") "
                + vinClauseX(vin)
                + " ORDER BY x.report_time DESC LIMIT 200";
        List<Object> params = new ArrayList<>();
        params.add(range.from().format(DT));
        params.add(range.to().format(DT));
        params.addAll(fleets);
        addVin(params, vin);
        return new Outcome(def, exec(def, sql, params));
    }

    // ---------- 里程 / 充电域 ----------

    /** 车队里程对比 */
    public Outcome mileageByFleet(TimeRanges.Range range, PermissionContext ctx) {
        CapabilityDefinition def = def("sim_fleet_mileage", "车队里程对比", "bar",
                col("fleet_name", "category", "车队", null),
                col("total_km", "metric", "总里程", "km"),
                col("avg_daily_km", "metric", "车均日里程", "km"),
                col("active_vehicles", "metric", "活跃车辆", "台"));
        List<String> fleets = aclFleets(ctx);
        long days = Math.max(1, java.time.Duration.between(range.from(), range.to()).toDays());
        String sql = "SELECT f.fleet_name, ROUND(SUM(m.mileage_km), 1) AS total_km, "
                + " ROUND(SUM(m.mileage_km) / (COUNT(DISTINCT m.vin) * " + days + "), 1) AS avg_daily_km, "
                + " COUNT(DISTINCT m.vin) AS active_vehicles "
                + " FROM " + SIM + ".fact_mileage_daily m "
                + " JOIN " + SIM + ".dim_vehicle v ON m.vin = v.vin "
                + " JOIN " + SIM + ".dim_fleet f ON v.fleet_id = f.fleet_id "
                + " WHERE m.dt >= ? AND m.dt < ? "
                + " AND v.fleet_id IN (" + placeholders(fleets.size()) + ") "
                + " GROUP BY f.fleet_name ORDER BY total_km DESC";
        List<Object> params = new ArrayList<>();
        params.add(range.from().format(DT));
        params.add(range.to().format(DT));
        params.addAll(fleets);
        return new Outcome(def, exec(def, sql, params));
    }

    /** 每日里程趋势（可指定单车） */
    public Outcome mileageDaily(TimeRanges.Range range, String vin, PermissionContext ctx) {
        CapabilityDefinition def = def("sim_mileage_daily", "每日里程趋势", "line",
                col("dt", "time", "日期", null),
                col("mileage_km", "metric", "里程", "km"),
                col("driving_minutes", "metric", "行驶时长", "分钟"));
        List<String> fleets = aclFleets(ctx);
        String sql = "SELECT m.dt, ROUND(SUM(m.mileage_km), 1) AS mileage_km, SUM(m.driving_minutes) AS driving_minutes "
                + " FROM " + SIM + ".fact_mileage_daily m "
                + " JOIN " + SIM + ".dim_vehicle v ON m.vin = v.vin "
                + " WHERE m.dt >= ? AND m.dt < ? "
                + " AND v.fleet_id IN (" + placeholders(fleets.size()) + ") "
                + vinClauseM(vin)
                + " GROUP BY m.dt ORDER BY m.dt LIMIT 120";
        List<Object> params = new ArrayList<>();
        params.add(range.from().format(DT));
        params.add(range.to().format(DT));
        params.addAll(fleets);
        addVin(params, vin);
        return new Outcome(def, exec(def, sql, params));
    }

    /** 充电统计：次数 / 电量 / 快充占比 */
    public Outcome chargeStats(TimeRanges.Range range, String vin, PermissionContext ctx) {
        CapabilityDefinition def = def("sim_charge_stats", "充电统计", "bar",
                col("dt", "time", "日期", null),
                col("charge_times", "metric", "充电次数", "次"),
                col("charge_kwh", "metric", "充电量", "kWh"),
                col("fast_ratio", "metric", "快充占比", "%"));
        List<String> fleets = aclFleets(ctx);
        String fast = "快充";
        String sql = "SELECT CAST(c.start_time AS DATE) AS dt, COUNT(1) AS charge_times, "
                + " ROUND(SUM(c.charge_kwh), 1) AS charge_kwh, "
                + " ROUND(SUM(CASE WHEN c.charge_type = ? THEN 1 ELSE 0 END) * 100.0 / COUNT(1), 1) AS fast_ratio "
                + " FROM " + SIM + ".fact_charge c "
                + " JOIN " + SIM + ".dim_vehicle v ON c.vin = v.vin "
                + " WHERE c.start_time >= ? AND c.start_time < ? "
                + " AND v.fleet_id IN (" + placeholders(fleets.size()) + ") "
                + vinClauseC(vin)
                + " GROUP BY CAST(c.start_time AS DATE) ORDER BY dt LIMIT 120";
        List<Object> params = new ArrayList<>();
        params.add(fast);
        params.add(range.from().format(DT));
        params.add(range.to().format(DT));
        params.addAll(fleets);
        addVin(params, vin);
        return new Outcome(def, exec(def, sql, params));
    }

    // ---------- 内部工具 ----------

    private static String placeholders(int n) {
        return String.join(", ", java.util.Collections.nCopies(Math.max(n, 1), "?"));
    }

    private static String vinClause(String vin) {
        return vin == null ? " " : " AND a.vin = ? ";
    }

    private static String vinClauseX(String vin) {
        return vin == null ? " " : " AND x.vin = ? ";
    }

    private static String vinClauseM(String vin) {
        return vin == null ? " " : " AND m.vin = ? ";
    }

    private static String vinClauseC(String vin) {
        return vin == null ? " " : " AND c.vin = ? ";
    }

    private static void addVin(List<Object> params, String vin) {
        if (vin == null) {
            return;
        }
        params.add(vin);
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

    /** 执行查询并组装结果表（不做行后处理） */
    private TableResult exec(CapabilityDefinition def, String sql, List<Object> params) {
        return exec(def, sql, params, null);
    }

    /** 执行查询并组装结果表；mapper 可对每行做后处理（如状态码转中文） */
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
