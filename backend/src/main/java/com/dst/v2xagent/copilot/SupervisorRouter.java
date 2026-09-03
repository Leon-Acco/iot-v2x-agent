package com.dst.v2xagent.copilot;

import com.dst.v2xagent.runtime.spi.StructuredModelClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * Supervisor 路由器：用结构化输出把用户问题分配给领域专家 Agent。
 * 红线：模型只输出路由标识 + 槽位，不产出 SQL / 不决定权限。
 */
@Slf4j
public class SupervisorRouter {

    /** 路由结果 */
    public record RouteResult(String route, String vehicle, String timeRange, String reason) {}

    private final StructuredModelClient structuredClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public SupervisorRouter(StructuredModelClient structuredClient) {
        this.structuredClient = structuredClient;
    }

    private static final String SCHEMA = "{"
            + "\"type\":\"object\","
            + "\"properties\":{"
            + "\"route\":{\"type\":\"string\",\"enum\":[\"vehicle\",\"alarm\",\"fault\",\"mileage\",\"anomaly\",\"cross\",\"meta\",\"chat\"]},"
            + "\"vehicle\":{\"type\":[\"string\",\"null\"]},"
            + "\"time_range\":{\"type\":[\"string\",\"null\"]},"
            + "\"reason\":{\"type\":\"string\"}"
            + "},"
            + "\"required\":[\"route\"]"
            + "}";

    private static final String SYSTEM =
            "你是车联网平台的总控路由 Agent。把用户问题分配给下列专家：\n"
            + "- vehicle：车辆档案、在离线状态、位置类问题\n"
            + "- alarm：告警统计、告警明细、车队告警对比\n"
            + "- fault：故障统计、故障明细\n"
            + "- mileage：里程、行驶时长、充电统计\n"
            + "- anomaly：针对某台车的异常原因探查（为什么频繁告警/最近怎么回事/哪里坏了），需要多源取证\n"
            + "- cross：需要跨多个数据域的综合问题，以及画图/流程图/拓扑图/可视化展示类请求\n"
            + "- meta：关于平台自身的问题（数据源是什么/你能做什么/有哪些能力/你是谁），不调工具\n"
            + "- chat：问候、闲聊、与车辆数据无关的问题\n"
            + "同时抽取槽位：vehicle（车牌号或 VIN，没有则 null）、time_range（原文时间词，没有则 null）。\n"
            + "多轮对话中，若用户用上下文指代（那台车/它/再看看），从历史里继承路由和车辆。";

    /** 路由一次对话；historyTail 为最近几条对话文本（用于指代继承） */
    public RouteResult route(String question, String historyTail) {
        try {
            String user = (historyTail == null || historyTail.isBlank() ? "" : "对话历史：\n" + historyTail + "\n\n")
                    + "当前问题：" + question;
            String json = structuredClient.generate(new StructuredModelClient.StructuredRequest(
                    SYSTEM, user, SCHEMA, 0.1, 15000));
            JsonNode node = mapper.readTree(json);
            return new RouteResult(
                    text(node, "route", "cross"),
                    text(node, "vehicle", null),
                    text(node, "time_range", null),
                    text(node, "reason", ""));
        } catch (Exception e) {
            log.warn("路由失败，降级为 cross: {}", e.getMessage());
            return new RouteResult("cross", null, null, "route fallback");
        }
    }

    private static String text(JsonNode node, String field, String dft) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return dft;
        }
        return v.asText();
    }
}
