package com.dst.v2xagent.copilot;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;

import java.util.ArrayList;
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
        return build(domain, tools, vizTools, null, model, null);
    }

    /**
     * 构建某领域的专家 Agent；schemaTools 仅 CROSS 挂载（原子探索工具）；
     * forcedTool 非空时注入"用户指定工具"指令
     * （强制路由：LLM 只负责把自然语言问题转成该工具参数，不再自由选工具）。
     */
    public static ReActAgent build(Domain domain, CopilotTools tools, VisualizationTools vizTools,
                                   SchemaTools schemaTools, Model model, String forcedTool) {
        Toolkit toolkit = new Toolkit();
        // 域内数据工具 + 能力说明书（loadSkill 模式）+ 任务固化（save_task，全域可用）
        List<String> enabled = new ArrayList<>(toolNames(domain));
        enabled.add("load_capability_guide");
        enabled.add("save_task");
        toolkit.registration().tool(tools).enableTools(List.copyOf(enabled)).apply();
        // 可视化/计算工具对所有专家开放
        toolkit.registration().tool(vizTools).apply();
        // schema 探索原子工具：只给 CROSS（跨域综合需要自己看数据长什么样）
        if (schemaTools != null && domain == Domain.CROSS) {
            toolkit.registration().tool(schemaTools).enableTools(
                    List.of("list_tables", "describe_table", "sample_rows")).apply();
        }
        String sysPrompt = prompt(domain);
        if (forcedTool != null && !forcedTool.isBlank()) {
            sysPrompt = sysPrompt + "\n11. 本轮用户已通过界面指定工具 " + forcedTool
                    + "。你必须首先调用该工具完成本轮任务：只把用户的自然语言问题转换成该工具的参数"
                    + "（时间范围/车辆等）；参数不足时先追问；除非用户明确要求，否则不要调用其他查询工具。";
        }
        return ReActAgent.builder()
                .name("v2x-" + domain.name().toLowerCase() + "-agent")
                .sysPrompt(sysPrompt)
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

    /** 各领域系统提示词（共享约束 + 领域职责 + 能力目录 + 专业语气约束） */
    private static String prompt(Domain domain) {
        String duty = switch (domain) {
            case VEHICLE -> "你负责车辆档案、在离线状态与位置类问题。";
            case ALARM -> "你负责告警统计、告警明细与车队告警对比类问题。";
            case FAULT -> "你负责故障统计与故障明细类问题。";
            case MILEAGE -> "你负责里程、行程与充电统计类问题。";
            case CROSS -> "你负责需要综合多个数据域的综合分析问题。"
                    + "你额外拥有 schema 探索工具（list_tables / describe_table / sample_rows）："
                    + "当不确定数据长什么样、或领域工具查不到时，可以先探索表结构、抽样看数据形态再决定查法；"
                    + "但正式统计与结论必须优先使用领域查询工具（计数/聚合口径更准）。";
        };
        return "你是车联网平台的数据分析 Agent。" + duty + "\n"
                + capabilityCatalog(domain) + "\n"
                + "规则：\n"
                + "1. 用户只给出领域、没给具体车辆或指标时，先调概览/对比类工具给出整体画面（表格图表会自动展示），再追问是否细看；禁止只反问不给数据。\n"
                + "2. 只能用工具返回的数据回答，严禁编造数字。\n"
                + "3. 用户没提时间范围时默认近 7 天。\n"
                + "4. 车辆指代（这台车/它）从对话历史中判断，无法判断就询问用户。\n"
                + "5. 表格和图表已由系统直接展示给用户，你的正文只给结论、关键数字和洞察，不要重复罗列全部数据。\n"
                + "6. 工具返回 not_found 时告诉用户没找到并引导换个说法；返回 ambiguous 时列出候选让用户确认。\n"
                + "7. 语气与风格——你是车联网平台的专业数据分析助手：\n"
                + "   - 语气专业、客观、书面化，结论先行、证据支撑；\n"
                + "   - 禁用 emoji、感叹号堆砌、拟人比喻与网络用语；\n"
                + "   - 发现异常时直接给出判断和处置建议，不铺垫、不渲染；\n"
                + "   - 红线：数字必须全部来自工具返回，一个都不许编；结论先把关键数字和判断说清楚，再给建议。\n"
                + "8. 预算治理（重要）：你最多有 6 轮迭代。当已调用 3 次及以上工具时，停止探索新方向，"
                + "基于已有证据组织最终结论；数据不足就如实说明缺什么，不要为凑齐而无限扩展查询。"
                + "先用 1~2 次工具给出整体画面，再按需深入，最后收敛作答——这个节奏比一次查全更重要。\n"
                + "9. 能力说明书：不确定选哪个工具、参数口径怎么填、或用户问法比较口语时，"
                + "先调 load_capability_guide 读该能力的说明书（示例问法/参数口径/输出形态）再调用；"
                + "含义明确的常见查询可直接调用，不必每次都读（每轮最多读 3 个）。\n"
                + "10. 任务固化：用户想把查询存为任务、设置定时自动执行（存为任务/保存这个查询/"
                + "每天几点自动查一遍/定时巡检）时，先完成用户要的查询，再调 save_task 固化"
                + "（cron_expr 填 6 位表达式，如每天08:00=0 0 8 * * *；仅手动则留空），"
                + "并在回复里告知任务已创建与下次执行时间。用户只是要求「把刚才的查询存下来」时直接调 save_task，"
                + "不必重复执行查询。\n";
    }

    /**
     * 域内能力目录（id + 一句话），注入提示词供模型语义匹配选工具；
     * 富说明书（参数口径/示例问法/图表形态）由 load_capability_guide 按需加载，不占提示词预算。
     */
    private static String capabilityCatalog(Domain domain) {
        List<String> names = toolNames(domain);
        StringBuilder sb = new StringBuilder("你可用能力目录（详细说明书可用 load_capability_guide 查阅）：\n");
        CopilotToolCatalog.list().stream()
                .filter(t -> names.contains(t.id()))
                .forEach(t -> sb.append("- ").append(t.id()).append("：").append(t.display())
                        .append("——").append(t.description()).append('\n'));
        return sb.toString();
    }
}
