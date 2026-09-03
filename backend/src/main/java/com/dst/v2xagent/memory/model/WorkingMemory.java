package com.dst.v2xagent.memory.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * 工作记忆（Redis，当前会话状态）
 * 只存上下文引用（result_id），不存完整查询结果。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkingMemory(
        String tenantId,
        Long userId,
        String sessionId,
        /** 当前业务上下文 */
        Map<String, Object> currentContext
) {
    /** 上下文键约定：fleet_id / time_range / selected_vehicle_ids / last_capability / last_result_id */
    public static final String KEY_FLEET_ID = "fleet_id";
    public static final String KEY_TIME_RANGE = "time_range";
    public static final String KEY_SELECTED_VINS = "selected_vehicle_ids";
    public static final String KEY_LAST_CAPABILITY = "last_capability";
    public static final String KEY_LAST_RESULT = "last_result_id";

    @SuppressWarnings("unchecked")
    public List<String> selectedVehicleIds() {
        Object v = currentContext == null ? null : currentContext.get(KEY_SELECTED_VINS);
        return v instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
    }
}
