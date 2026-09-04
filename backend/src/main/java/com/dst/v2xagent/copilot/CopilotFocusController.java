package com.dst.v2xagent.copilot;

import com.dst.v2xagent.agui.auth.AuthFilter;
import com.dst.v2xagent.common.PermissionContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


/**
 * 工作台「今日需要关注」摘要条数据源（人找数 -> 数找人）。
 * 轻量聚合：离线超 24h 车辆数、近 24h 告警数与 Top3 类型、数据截至时间。
 * 60 秒内存缓存（按权限范围隔离），点击条目由前端发起对应 Agent 查询。
 */
@Slf4j
@RestController
@RequestMapping("/ag-ui/copilot")
public class CopilotFocusController {

    private final CopilotQueryRouter queries;

    public CopilotFocusController(CopilotQueryRouter queries) {
        this.queries = queries;
    }

    private record CachedResult(long at, Map<String, Object> data) {}

    private final Map<String, CachedResult> cache = new ConcurrentHashMap<>();
    private static final long TTL_MS = 60_000L;

    /** 今日关注摘要：离线 / 告警 / 数据新鲜度（org ACL 内） */
    @GetMapping("/today-focus")
    public Map<String, Object> todayFocus(HttpServletRequest request) {
        PermissionContext ctx = (PermissionContext) request.getAttribute(AuthFilter.ATTR_PERMISSION);
        if (ctx == null) {
            return Map.of("error", "UNAUTHORIZED");
        }
        String scope = ctx.isAdmin() ? "admin" : String.join(",", ctx.fleetIds());
        CachedResult c = cache.get(scope);
        long now = System.currentTimeMillis();
        if (c != null && now - c.at() < TTL_MS) {
            return c.data();
        }
        Map<String, Object> data = cachedFocus(ctx, scope);
        cache.put(scope, new CachedResult(now, data));
        return data;
    }

    /** 聚合实现：异常直接吞掉返回空摘要（摘要条不可拖垮工作台） */
    private Map<String, Object> cachedFocus(PermissionContext ctx, String scope) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sourceName", queries.sourceName());
        out.put("jdbcUrl", queries.analyticsUrl());
        out.put("generatedAt", LocalDateTime.now().withNano(0).toString());
        try {
            SimQueries.Outcome offline = queries.offlineVehicles(24, ctx);
            out.put("offline24h", offline.table().rowCount());
            if (offline.table().freshness() != null) {
                out.put("dataAsOf", offline.table().freshness().dataAsOf());
            }
        } catch (Exception e) {
            log.warn("today-focus offline agg failed: {}", e.getMessage());
            out.put("offline24h", null);
        }
        try {
            LocalDateTime to = queries.baseDate().atStartOfDay().plusDays(1);
            TimeRanges.Range day = new TimeRanges.Range(to.minusDays(1), to, "近 1 天");
            SimQueries.Outcome alarms = queries.alarmsByType(day, null, ctx);
            out.put("alarm24h", alarms.table().rowCount() == 0 ? 0 : totalAlarmCount(alarms));
            out.put("alarmTop", topAlarmTypes(alarms, 3));
        } catch (Exception e) {
            log.warn("today-focus alarm agg failed: {}", e.getMessage());
            out.put("alarm24h", null);
        }
        return out;
    }

    /** 告警总量：metric 列求和（按类型统计表的行为 类型 x 次数） */
    private long totalAlarmCount(SimQueries.Outcome oc) {
        int metricIdx = metricIndex(oc);
        if (metricIdx < 0) {
            return oc.table().rowCount();
        }
        long sum = 0;
        for (Object[] row : oc.table().rows()) {
            try {
                sum += Long.parseLong(String.valueOf(row[metricIdx]).replaceAll("[^0-9]", ""));
            } catch (Exception ignore) {
                // 非数值行跳过
            }
        }
        return sum;
    }

    /** 告警 Top N 类型：category 标签 + metric 度量 */
    private List<Map<String, Object>> topAlarmTypes(SimQueries.Outcome oc, int n) {
        List<Map<String, Object>> list = new ArrayList<>();
        int labelIdx = 0;
        int metricIdx = metricIndex(oc);
        int limit = Math.min(n, oc.table().rowCount());
        for (int r = 0; r < limit; r++) {
            Object[] row = oc.table().rows().get(r);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", String.valueOf(row[labelIdx]));
            item.put("count", metricIdx >= 0 ? row[metricIdx] : null);
            list.add(item);
        }
        return list;
    }

    /** 首个 metric 语义列下标 */
    private int metricIndex(SimQueries.Outcome oc) {
        for (int i = 0; i < oc.def().getReturns().getColumns().size(); i++) {
            if ("metric".equals(oc.def().getReturns().getColumns().get(i).getSemantic())) {
                return i;
            }
        }
        return -1;
    }
}
