package com.dst.v2xagent.copilot;

import com.dst.v2xagent.common.PermissionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Copilot 工具集（AgentScope @Tool 注解驱动，每次运行构造一个实例）。
 * 每个工具：解析参数 -> 调 SimQueries（确定性 SQL + ACL）-> 桥推表格/图表给前端
 * -> 返回紧凑摘要 JSON 给模型。模型看不到 SQL，也碰不到权限。
 */
public class CopilotTools {

    private final CopilotQueryRouter queries;
    private final PermissionContext ctx;
    private final ToolResultBridge bridge;
    private final ObjectMapper mapper = new ObjectMapper();

    public CopilotTools(CopilotQueryRouter queries, PermissionContext ctx, ToolResultBridge bridge) {
        this.queries = queries;
        this.ctx = ctx;
        this.bridge = bridge;
    }

    // ---------- 车辆域 ----------

    /** 查车辆档案 */
    @Tool(name = "query_vehicle_info", description = "查询车辆档案信息（车牌、车队、品牌车型、电池、状态）")
    public String queryVehicleInfo(
            @ToolParam(name = "vehicle", required = true, description = "车牌号或 VIN，支持模糊") String vehicle) {
        String vin = resolveVin(vehicle);
        if (vin == null) {
            return jsonMsg("not_found", "未找到匹配车辆：" + vehicle);
        }
        if (vin.startsWith("AMBIGUOUS:")) {
            return vin.substring("AMBIGUOUS:".length());
        }
        SimQueries.Outcome oc = queries.vehicleInfo(vin, ctx);
        bridge.emitOutcome(oc, Map.of("vehicle", vehicle));
        return summarize(oc, "车辆档案已展示");
    }

    /** 离线车辆清单 */
    @Tool(name = "list_offline_vehicles", description = "查询离线超过 N 小时的车辆清单（默认 24 小时）")
    public String listOfflineVehicles(
            @ToolParam(name = "hours", required = false, description = "离线时长阈值（小时），默认 24") Integer hours) {
        int h = hours == null || hours <= 0 ? 24 : hours;
        SimQueries.Outcome oc = queries.offlineVehicles(h, ctx);
        bridge.emitOutcome(oc, Map.of("offline_hours>=", h));
        return summarize(oc, "离线超 " + h + " 小时车辆 " + oc.table().rowCount() + " 台");
    }

    /** 车辆最后位置 */
    @Tool(name = "query_vehicle_location", description = "查询车辆最后上报位置（经纬度、城市、里程表）")
    public String queryVehicleLocation(
            @ToolParam(name = "vehicle", required = true, description = "车牌号或 VIN") String vehicle) {
        String vin = resolveVin(vehicle);
        if (vin == null) {
            return jsonMsg("not_found", "未找到匹配车辆：" + vehicle);
        }
        if (vin.startsWith("AMBIGUOUS:")) {
            return vin.substring("AMBIGUOUS:".length());
        }
        SimQueries.Outcome oc = queries.vehicleLocation(vin, ctx);
        bridge.emitOutcome(oc, Map.of("vehicle", vehicle));
        return summarize(oc, "最后位置已展示");
    }

    // ---------- 告警域 ----------

    /** 告警类型统计 */
    @Tool(name = "count_alarms_by_type", description = "统计时间范围内各类告警次数，可指定单车")
    public String countAlarmsByType(
            @ToolParam(name = "time_range", required = false, description = "时间范围，如 近7天/昨天/近30天，默认近7天") String timeRange,
            @ToolParam(name = "vehicle", required = false, description = "车牌号或 VIN，可空") String vehicle) {
        TimeRanges.Range range = TimeRanges.parse(timeRange, queries.baseDate());
        String vin = resolveVinOrNull(vehicle);
        if (vehicle != null && vin == null) {
            return jsonMsg("not_found", "未找到匹配车辆：" + vehicle);
        }
        if (ambiguous(vin)) {
            return vin.substring("AMBIGUOUS:".length());
        }
        SimQueries.Outcome oc = queries.alarmsByType(range, vin, ctx);
        bridge.emitOutcome(oc, argsOf(range, vehicle));
        return summarize(oc, range.display() + " 告警类型统计已展示");
    }

    /** 告警明细 */
    @Tool(name = "list_alarms", description = "查询告警明细列表，可指定车辆与时间范围")
    public String listAlarms(
            @ToolParam(name = "time_range", required = false, description = "时间范围，默认近7天") String timeRange,
            @ToolParam(name = "vehicle", required = false, description = "车牌号或 VIN，可空") String vehicle,
            @ToolParam(name = "limit", required = false, description = "返回条数，默认 50，上限 200") Integer limit) {
        TimeRanges.Range range = TimeRanges.parse(timeRange, queries.baseDate());
        String vin = resolveVinOrNull(vehicle);
        if (vehicle != null && vin == null) {
            return jsonMsg("not_found", "未找到匹配车辆：" + vehicle);
        }
        if (ambiguous(vin)) {
            return vin.substring("AMBIGUOUS:".length());
        }
        int top = limit == null ? 50 : limit;
        SimQueries.Outcome oc = queries.alarmList(range, vin, top, ctx);
        bridge.emitOutcome(oc, argsOf(range, vehicle));
        return summarize(oc, range.display() + " 告警明细 " + oc.table().rowCount() + " 条");
    }

    /** 车队告警对比 */
    @Tool(name = "compare_fleet_alarms", description = "对比各车队告警总量、严重告警与百车日均告警")
    public String compareFleetAlarms(
            @ToolParam(name = "time_range", required = false, description = "时间范围，默认近7天") String timeRange) {
        TimeRanges.Range range = TimeRanges.parse(timeRange, queries.baseDate());
        SimQueries.Outcome oc = queries.fleetAlarmCompare(range, ctx);
        bridge.emitOutcome(oc, argsOf(range, null));
        return summarize(oc, range.display() + " 车队告警对比已展示");
    }

    // ---------- 故障域 ----------

    /** 故障部位统计 */
    @Tool(name = "count_faults_by_part", description = "统计时间范围内各部位故障次数与未恢复数，可指定单车")
    public String countFaultsByPart(
            @ToolParam(name = "time_range", required = false, description = "时间范围，默认近7天") String timeRange,
            @ToolParam(name = "vehicle", required = false, description = "车牌号或 VIN，可空") String vehicle) {
        TimeRanges.Range range = TimeRanges.parse(timeRange, queries.baseDate());
        String vin = resolveVinOrNull(vehicle);
        if (vehicle != null && vin == null) {
            return jsonMsg("not_found", "未找到匹配车辆：" + vehicle);
        }
        if (ambiguous(vin)) {
            return vin.substring("AMBIGUOUS:".length());
        }
        SimQueries.Outcome oc = queries.faultsByPart(range, vin, ctx);
        bridge.emitOutcome(oc, argsOf(range, vehicle));
        return summarize(oc, range.display() + " 故障部位统计已展示");
    }

    /** 故障明细 */
    @Tool(name = "list_faults", description = "查询故障明细，可只看未恢复故障")
    public String listFaults(
            @ToolParam(name = "time_range", required = false, description = "时间范围，默认近7天") String timeRange,
            @ToolParam(name = "vehicle", required = false, description = "车牌号或 VIN，可空") String vehicle,
            @ToolParam(name = "active_only", required = false, description = "是否只看未恢复，默认 false") Boolean activeOnly) {
        TimeRanges.Range range = TimeRanges.parse(timeRange, queries.baseDate());
        String vin = resolveVinOrNull(vehicle);
        if (vehicle != null && vin == null) {
            return jsonMsg("not_found", "未找到匹配车辆：" + vehicle);
        }
        if (ambiguous(vin)) {
            return vin.substring("AMBIGUOUS:".length());
        }
        SimQueries.Outcome oc = queries.faultList(range, vin, Boolean.TRUE.equals(activeOnly), ctx);
        bridge.emitOutcome(oc, argsOf(range, vehicle));
        return summarize(oc, range.display() + " 故障明细 " + oc.table().rowCount() + " 条");
    }

    // ---------- 里程 / 充电域 ----------

    /** 车队里程对比 */
    @Tool(name = "compare_fleet_mileage", description = "对比各车队总里程、车均日里程与活跃车辆数")
    public String compareFleetMileage(
            @ToolParam(name = "time_range", required = false, description = "时间范围，默认近7天") String timeRange) {
        TimeRanges.Range range = TimeRanges.parse(timeRange, queries.baseDate());
        SimQueries.Outcome oc = queries.mileageByFleet(range, ctx);
        bridge.emitOutcome(oc, argsOf(range, null));
        return summarize(oc, range.display() + " 车队里程对比已展示");
    }

    /** 每日里程趋势 */
    @Tool(name = "query_mileage_daily", description = "查询每日里程与行驶时长趋势，可指定单车")
    public String queryMileageDaily(
            @ToolParam(name = "time_range", required = false, description = "时间范围，默认近7天") String timeRange,
            @ToolParam(name = "vehicle", required = false, description = "车牌号或 VIN，可空") String vehicle) {
        TimeRanges.Range range = TimeRanges.parse(timeRange, queries.baseDate());
        String vin = resolveVinOrNull(vehicle);
        if (vehicle != null && vin == null) {
            return jsonMsg("not_found", "未找到匹配车辆：" + vehicle);
        }
        if (ambiguous(vin)) {
            return vin.substring("AMBIGUOUS:".length());
        }
        SimQueries.Outcome oc = queries.mileageDaily(range, vin, ctx);
        bridge.emitOutcome(oc, argsOf(range, vehicle));
        return summarize(oc, range.display() + " 里程趋势已展示");
    }

    /** 充电统计 */
    @Tool(name = "query_charge_stats", description = "统计每日充电次数、电量与快充占比，可指定单车")
    public String queryChargeStats(
            @ToolParam(name = "time_range", required = false, description = "时间范围，默认近7天") String timeRange,
            @ToolParam(name = "vehicle", required = false, description = "车牌号或 VIN，可空") String vehicle) {
        TimeRanges.Range range = TimeRanges.parse(timeRange, queries.baseDate());
        String vin = resolveVinOrNull(vehicle);
        if (vehicle != null && vin == null) {
            return jsonMsg("not_found", "未找到匹配车辆：" + vehicle);
        }
        if (ambiguous(vin)) {
            return vin.substring("AMBIGUOUS:".length());
        }
        SimQueries.Outcome oc = queries.chargeStats(range, vin, ctx);
        bridge.emitOutcome(oc, argsOf(range, vehicle));
        return summarize(oc, range.display() + " 充电统计已展示");
    }

    // ---------- 内部工具 ----------

    /** 解析车辆参数：唯一命中返回 VIN；多命中返回 AMBIGUOUS:前缀 + 候选 JSON；无命中返回 null */
    private String resolveVin(String vehicle) {
        List<Map<String, Object>> candidates = queries.resolveVehicles(vehicle, ctx);
        if (candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() == 1) {
            return String.valueOf(candidates.get(0).get("vin"));
        }
        // 精确车牌命中优先
        for (Map<String, Object> c : candidates) {
            if (vehicle.trim().equalsIgnoreCase(String.valueOf(c.get("plate_no")))) {
                return String.valueOf(c.get("vin"));
            }
        }
        return "AMBIGUOUS:" + candidateJson(candidates);
    }

    private String resolveVinOrNull(String vehicle) {
        return vehicle == null || vehicle.isBlank() ? null : resolveVin(vehicle);
    }

    private boolean ambiguous(String vin) {
        return vin != null && vin.startsWith("AMBIGUOUS:");
    }

    private Map<String, Object> argsOf(TimeRanges.Range range, String vehicle) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("time_range_display", range.display());
        if (vehicle != null) {
            m.put("vehicle", vehicle);
        }
        return m;
    }

    /** 给模型的紧凑摘要：小结果全量，大结果只给前 10 行 + 总数 */
    private String summarize(SimQueries.Outcome oc, String note) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("note", note);
        out.put("rowCount", oc.table().rowCount());
        List<String> cols = oc.def().getReturns().getColumns().stream()
                .map(c -> c.getDisplay() + (c.getUnit() == null ? "" : "(" + c.getUnit() + ")"))
                .toList();
        out.put("columns", cols);
        int limit = oc.table().rowCount() <= 20 ? oc.table().rowCount() : 10;
        List<List<Object>> rows = new java.util.ArrayList<>();
        for (int i = 0; i < limit; i++) {
            List<Object> row = new java.util.ArrayList<>();
            for (Object cell : oc.table().rows().get(i)) {
                row.add(cell == null ? null : cell.toString());
            }
            rows.add(row);
        }
        out.put("rows", rows);
        if (oc.table().rowCount() > limit) {
            out.put("truncatedNote", "仅展示前 " + limit + " 行，完整表格已推送前端");
        }
        return toJson(out);
    }

    private String candidateJson(List<Map<String, Object>> candidates) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ambiguous");
        out.put("message", "找到多台匹配车辆，请用户确认是哪一台");
        out.put("candidates", candidates.stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("plate_no", c.get("plate_no"));
            m.put("vin", c.get("vin"));
            m.put("fleet_name", c.get("fleet_name"));
            return m;
        }).toList());
        return toJson(out);
    }

    private String jsonMsg(String status, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", status);
        out.put("message", message);
        return toJson(out);
    }

    private String toJson(Object v) {
        try {
            return mapper.writeValueAsString(v);
        } catch (Exception e) {
            return String.valueOf(v);
        }
    }
}
