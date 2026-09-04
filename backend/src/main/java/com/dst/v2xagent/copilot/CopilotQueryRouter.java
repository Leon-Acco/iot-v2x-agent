package com.dst.v2xagent.copilot;

import com.dst.v2xagent.common.PermissionContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 查询源路由：copilot.datasource=real|sim 切换真实库 / 模拟库（默认 real）。
 * 工具层只依赖本类，不感知底层数据源。
 */
@Component
public class CopilotQueryRouter {

    private final RealQueries real;
    private final SimQueries sim;
    private final String source;

    public CopilotQueryRouter(RealQueries real, SimQueries sim,
                              @Value("${copilot.datasource:real}") String source) {
        this.real = real;
        this.sim = sim;
        this.source = source;
    }

    /** 当前生效数据源标识（给 meta 回答用，库名取自真实连接避免文案漂移） */
    public String sourceName() {
        if (isSim()) {
            return "模拟库 dst_v2x_sim";
        }
        String url = real.analyticsUrl();
        int slash = url.lastIndexOf('/');
        int q = url.indexOf('?', slash);
        String db = url.substring(slash + 1, q > 0 ? q : url.length());
        return "Doris 分析库 " + db;
    }

    /** 排障用：底层 analytics JDBC 连接 URL */
    public String analyticsUrl() {
        return isSim() ? "sim" : real.analyticsUrl();
    }

    private boolean isSim() {
        return "sim".equalsIgnoreCase(source);
    }

    public LocalDate baseDate() {
        return isSim() ? sim.baseDate() : real.baseDate();
    }

    public List<Map<String, Object>> resolveVehicles(String vehicle, PermissionContext ctx) {
        return isSim() ? sim.resolveVehicles(vehicle, ctx) : real.resolveVehicles(vehicle, ctx);
    }

    public SimQueries.Outcome vehicleInfo(String vin, PermissionContext ctx) {
        return isSim() ? sim.vehicleInfo(vin, ctx) : real.vehicleInfo(vin, ctx);
    }

    public SimQueries.Outcome offlineVehicles(int hours, PermissionContext ctx) {
        return isSim() ? sim.offlineVehicles(hours, ctx) : real.offlineVehicles(hours, ctx);
    }

    public SimQueries.Outcome vehicleLocation(String vin, PermissionContext ctx) {
        return isSim() ? sim.vehicleLocation(vin, ctx) : real.vehicleLocation(vin, ctx);
    }

    public SimQueries.Outcome alarmsByType(TimeRanges.Range range, String vin, PermissionContext ctx) {
        return isSim() ? sim.alarmsByType(range, vin, ctx) : real.alarmsByType(range, vin, ctx);
    }

    public SimQueries.Outcome alarmList(TimeRanges.Range range, String vin, int limit, PermissionContext ctx) {
        return isSim() ? sim.alarmList(range, vin, limit, ctx) : real.alarmList(range, vin, limit, ctx);
    }

    public SimQueries.Outcome fleetAlarmCompare(TimeRanges.Range range, PermissionContext ctx) {
        return isSim() ? sim.fleetAlarmCompare(range, ctx) : real.fleetAlarmCompare(range, ctx);
    }

    public SimQueries.Outcome faultsByPart(TimeRanges.Range range, String vin, PermissionContext ctx) {
        return isSim() ? sim.faultsByPart(range, vin, ctx) : real.faultsByPart(range, vin, ctx);
    }

    public SimQueries.Outcome faultList(TimeRanges.Range range, String vin, boolean activeOnly, PermissionContext ctx) {
        return isSim() ? sim.faultList(range, vin, activeOnly, ctx) : real.faultList(range, vin, activeOnly, ctx);
    }

    public SimQueries.Outcome mileageByFleet(TimeRanges.Range range, PermissionContext ctx) {
        return isSim() ? sim.mileageByFleet(range, ctx) : real.mileageByFleet(range, ctx);
    }

    public SimQueries.Outcome mileageDaily(TimeRanges.Range range, String vin, PermissionContext ctx) {
        return isSim() ? sim.mileageDaily(range, vin, ctx) : real.mileageDaily(range, vin, ctx);
    }

    public SimQueries.Outcome chargeStats(TimeRanges.Range range, String vin, PermissionContext ctx) {
        return isSim() ? sim.chargeStats(range, vin, ctx) : real.chargeStats(range, vin, ctx);
    }
}
