package com.dst.v2xagent.capability;

import com.dst.v2xagent.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 车辆实体解析：车牌 / VIN 全码 / VIN 后 6 位 / 自定义编号 → vin_list
 * 解析必须先过数据权限：用户看不到的车辆直接视为不存在（而不是报「无权限」，避免探测式枚举）。
 */
@Component
public class VehicleResolver {

    private final JdbcTemplate analyticsJdbcTemplate;

    /** 显式注入分析库 JdbcTemplate（双数据源强隔离） */
    public VehicleResolver(@Qualifier("analyticsJdbcTemplate") JdbcTemplate analyticsJdbcTemplate) {
        this.analyticsJdbcTemplate = analyticsJdbcTemplate;
    }

    /** 解析结果 */
    public record ResolveResult(List<String> vins, List<VehicleCandidate> candidates) {
        public boolean ambiguous() { return candidates.size() > 1; }
        public boolean notFound() { return candidates.isEmpty(); }
    }

    /** 候选车辆（澄清时展示：车牌 + VIN 后 6 位 + 车队） */
    public record VehicleCandidate(String vin, String plateNo, String fleetName) {}

    /**
     * 把用户输入的车辆标识解析为 VIN 列表（限权限车队范围内）。
     * @param input      车牌 / VIN / VIN 后 6 位
     * @param aclFleetIds 权限车队集合
     */
    public ResolveResult resolve(String input, Set<String> aclFleetIds) {
        if (input == null || input.isBlank() || aclFleetIds == null || aclFleetIds.isEmpty()) {
            return new ResolveResult(List.of(), List.of());
        }
        String kw = input.trim();
        // ACL 过滤与模糊匹配在同一查询中（不泄露无权限车辆的存在性）
        String fleetPlaceholders = String.join(", ", java.util.Collections.nCopies(aclFleetIds.size(), "?"));
        // 真实数据源：basic_vehicle_info（org_name 即车队；del_flag=0 均为在册）
        String sql = """
                SELECT vin_code AS vin, car_no AS plate_no, org_name AS fleet_name FROM basic_vehicle_info
                WHERE org_name IN (%s)
                  AND (vin_code = ? OR car_no = ? OR RIGHT(vin_code, 6) = ? OR car_no LIKE ?)
                LIMIT 20
                """.formatted(fleetPlaceholders);
        List<Object> args = new ArrayList<>(aclFleetIds);
        args.add(kw);
        args.add(kw);
        args.add(kw.length() >= 6 ? kw.substring(kw.length() - 6) : kw);
        args.add("%" + kw + "%");
        List<VehicleCandidate> candidates = analyticsJdbcTemplate.query(sql, (rs, i) ->
                new VehicleCandidate(rs.getString("vin"), rs.getString("plate_no"), rs.getString("fleet_name")),
                args.toArray());
        // 去重保持顺序
        Set<String> vins = new LinkedHashSet<>();
        candidates.forEach(c -> vins.add(c.vin()));
        return new ResolveResult(List.copyOf(vins), candidates);
    }
}
