package com.dst.v2xagent.copilot;

import java.util.List;
import java.util.Map;

/**
 * Copilot 工具静态目录：运营侧"用户选择工具"清单（GET /ag-ui/copilot/tools）。
 * display 与 SimQueries/RealQueries 的 def() 中文名对齐；domain 分组与 SpecialistAgents.toolNames 一致。
 * registryId = capability 目录（任务卡/试跑体系）中的同义能力 id，null 表示无对应（不支持生成任务卡）。
 */
public final class CopilotToolCatalog {

    private CopilotToolCatalog() {}

    /** 单个工具条目 */
    public record ToolMeta(String id, String display, String domain, String description,
                           String registryId) {}

    private static final List<ToolMeta> TOOLS = List.of(
            new ToolMeta("query_vehicle_info", "车辆档案", "VEHICLE", "按车牌/VIN 查询车辆基础档案信息", null),
            new ToolMeta("list_offline_vehicles", "离线车辆清单", "VEHICLE", "查询离线超时的车辆清单", "offline_vehicle_list"),
            new ToolMeta("query_vehicle_location", "最后位置", "VEHICLE", "查询车辆最后一次上报位置", null),
            new ToolMeta("count_alarms_by_type", "告警类型统计", "ALARM", "按告警类型聚合计数", "alarm_count_by_type"),
            new ToolMeta("list_alarms", "告警明细", "ALARM", "查询告警明细列表", "alarm_list"),
            new ToolMeta("compare_fleet_alarms", "车队告警对比", "ALARM", "多车队告警对比分析", null),
            new ToolMeta("count_faults_by_part", "故障部位统计", "FAULT", "按故障部位聚合计数", "fault_count_by_part"),
            new ToolMeta("list_faults", "故障明细", "FAULT", "查询故障明细列表", "fault_list"),
            new ToolMeta("compare_fleet_mileage", "车队里程对比", "MILEAGE", "多车队里程对比分析", "mileage_by_fleet"),
            new ToolMeta("query_mileage_daily", "每日里程趋势", "MILEAGE", "按日查询车辆里程趋势", "mileage_daily"),
            new ToolMeta("query_charge_stats", "充电统计", "MILEAGE", "按日查询充电次数/电量/时长", "charge_daily"));

    /** 全量目录（REST 出口用，返回不可变副本） */
    public static List<ToolMeta> list() {
        return TOOLS;
    }

    /** 工具是否存在 */
    public static boolean exists(String toolId) {
        return TOOLS.stream().anyMatch(t -> t.id().equals(toolId));
    }

    /** 工具名 -> 所属领域（与 SpecialistAgents.toolNames 白名单一致，不存在抛出 IllegalArgumentException） */
    public static SpecialistAgents.Domain domainOf(String toolId) {
        for (SpecialistAgents.Domain domain : SpecialistAgents.Domain.values()) {
            if (SpecialistAgents.toolNames(domain).contains(toolId)) {
                return domain;
            }
        }
        throw new IllegalArgumentException("unknown tool: " + toolId);
    }

    /** 工具中文名（提示词注入用），未知返回工具 id 本身 */
    public static String displayOf(String toolId) {
        return TOOLS.stream().filter(t -> t.id().equals(toolId))
                .map(ToolMeta::display).findFirst().orElse(toolId);
    }

    /**
     * copilot 工具（id 或中文名）-> capability 目录 id；无对应返回 null。
     * 任务卡体系用：对话流工具与 capability 是两套 id 体系，靠本表对齐。
     */
    public static String registryIdOf(String toolIdOrDisplay) {
        if (toolIdOrDisplay == null || toolIdOrDisplay.isBlank()) {
            return null;
        }
        String key = toolIdOrDisplay.split("#")[0].trim();
        for (ToolMeta t : TOOLS) {
            if (t.id().equals(key) || t.display().equals(key)) {
                return t.registryId();
            }
        }
        return null;
    }

    /** REST 序列化视图 */
    public static List<Map<String, Object>> asMaps() {
        return TOOLS.stream().map(t -> Map.<String, Object>of(
                "id", t.id(), "display", t.display(),
                "domain", t.domain(), "description", t.description())).toList();
    }
}
