package com.dst.v2xagent.copilot;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;

import java.util.List;

/**
 * 专家 Agent 工厂：每个领域一个 ReActAgent，Toolkit 只放本域工具。
 * 多 Agent 结构：Supervisor 路由 -> 领域专家（本类）-> 工具（确定性 SQL）。
 */
public final class SpecialistAgents {

    private SpecialistAgents() {}

    /** 领域枚举：CROSS 为跨域综合问题准备（拥有全部工具） */
    public enum Domain { VEHICLE, ALARM, FAULT, MILEAGE, CROSS }

    /** 构建某领域的专家 Agent */
    public static ReActAgent build(Domain domain, CopilotTools tools, VisualizationTools vizTools, Model model) {
        Toolkit toolkit = new Toolkit();
        toolkit.registration().tool(tools).enableTools(toolNames(domain)).apply();
        // 可视化/计算工具对所有专家开放
        toolkit.registration().tool(vizTools).apply();
        return ReActAgent.builder()
                .name("v2x-" + domain.name().toLowerCase() + "-agent")
                .sysPrompt(prompt(domain))
                .model(model)
                .toolkit(toolkit)
                .maxIters(6)
                .build();
    }

    /** 领域 -> 工具名白名单 */
    public static List<String> toolNames(Domain domain) {
        return switch (domain) {
            case VEHICLE -> List.of("query_vehicle_info", "list_offline_vehicles", "query_vehicle_location");
            case ALARM -> List.of("count_alarms_by_type", "list_alarms", "compare_fleet_alarms");
            case FAULT -> List.of("count_faults_by_part", "list_faults");
            case MILEAGE -> List.of("compare_fleet_mileage", "query_mileage_daily", "query_charge_stats");
            case CROSS -> List.of("query_vehicle_info", "list_offline_vehicles", "query_vehicle_location",
                    "count_alarms_by_type", "list_alarms", "compare_fleet_alarms",
                    "count_faults_by_part", "list_faults",
                    "compare_fleet_mileage", "query_mileage_daily", "query_charge_stats");
        };
    }

    /** 各领域系统提示词（共享约束 + 领域职责） */
    private static String prompt(Domain domain) {
        String duty = switch (domain) {
            case VEHICLE -> "你负责车辆档案、在离线状态与位置类问题。";
            case ALARM -> "你负责告警统计、告警明细与车队告警对比类问题。";
            case FAULT -> "你负责故障统计与故障明细类问题。";
            case MILEAGE -> "你负责里程、行程与充电统计类问题。";
            case CROSS -> "你负责需要综合多个数据域的综合分析问题。";
        };
        return "你是车联网平台的数据分析 Agent。" + duty + "\n"
                + "规则：\n"
                + "1. 用户只给出领域、没给具体车辆或指标时，先调概览/对比类工具给出整体画面（表格图表会自动展示），再追问是否细看；禁止只反问不给数据。\n"
                + "2. 只能用工具返回的数据回答，严禁编造数字。\n"
                + "3. 用户没提时间范围时默认近 7 天。\n"
                + "4. 车辆指代（这台车/它）从对话历史中判断，无法判断就询问用户。\n"
                + "5. 表格和图表已由系统直接展示给用户，你的正文只给结论、关键数字和洞察，不要重复罗列全部数据。\n"
                + "6. 工具返回 not_found 时告诉用户没找到并引导换个说法；返回 ambiguous 时列出候选让用户确认。\n"
                + "7. 用简洁专业的中文回答。";
    }
}
