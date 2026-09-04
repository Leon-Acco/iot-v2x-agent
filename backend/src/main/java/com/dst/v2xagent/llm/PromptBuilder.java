package com.dst.v2xagent.llm;

import com.dst.v2xagent.capability.model.CapabilityDefinition;
import com.dst.v2xagent.runtime.spi.StructuredModelClient;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 抽取 prompt 与工具定义构建器（真实模型模式）
 * 候选 capability 以原生 function calling 工具形式下发，定义与 Java 校验器、管理后台表单同源。
 */
public final class PromptBuilder {

    private PromptBuilder() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 拒答兜底工具名：用户问题与所有能力无关时由模型调用 */
    public static final String REJECT_TOOL = "reject_question";

    /** 抽取系统 prompt：角色 + 红线（稳定前缀，利于 Prompt Caching） */
    public static final String EXTRACT_SYSTEM = """
            你是车联网数据助手的能力路由模块。根据用户问题，从提供的候选工具中选择最合适的一个并调用，同时填写参数。
            红线（必须遵守）：
            1. 只能调用提供的工具，绝不编造工具名、参数名或参数值。
            2. 时间参数一律填自然语言表达式（如「近7天」「昨天」），不要自己换算绝对日期。
            3. 车辆标识（车牌/VIN）填到 vehicle 参数；不要把权限相关字段（如 acl_*）放入参数。
            4. 每次调用必须携带 confidence 参数（0~1 的小数，表示选择该工具的置信度；拿不准时给低值）。
            5. 如果用户问题与所有候选工具都无关（闲聊、问候、常识问答、与车辆数据无关），调用 reject_question，不得勉强选择。
            """;

    /**
     * 候选 capability → function calling 工具列表：
     * 每个能力一个工具（name=id、description=名称+说明+别名、parameters 由 ParamDef 映射），
     * 末尾追加 reject_question 兜底工具。
     */
    public static List<StructuredModelClient.ToolSpec> buildToolSpecs(List<CapabilityDefinition> candidates) {
        List<StructuredModelClient.ToolSpec> tools = new ArrayList<>();
        for (CapabilityDefinition def : candidates) {
            tools.add(new StructuredModelClient.ToolSpec(
                    def.getId(), toolDescription(def), toolParameters(def)));
        }
        tools.add(new StructuredModelClient.ToolSpec(
                REJECT_TOOL,
                "用户问题与所有数据查询能力都无关时调用（闲聊、问候、常识问答、与车辆数据无关的请求）",
                Map.of("type", "object",
                        "properties", Map.of("reason", Map.of(
                                "type", "string", "description", "判定为无关问题的简短原因")),
                        "additionalProperties", false)));
        return tools;
    }

    /** 工具描述：名称 + 说明 + 别名（同义词辅助模型匹配） */
    private static String toolDescription(CapabilityDefinition def) {
        StringBuilder sb = new StringBuilder(def.getDisplay()).append("：").append(def.getDescription());
        if (def.getAliases() != null && !def.getAliases().isEmpty()) {
            sb.append("（又称：").append(String.join("、", def.getAliases())).append("）");
        }
        return sb.toString();
    }

    /** 工具参数 schema：ParamDef → JSON Schema，并注入可选 confidence 供决策表使用 */
    private static Map<String, Object> toolParameters(CapabilityDefinition def) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        if (def.getParams() != null) {
            for (CapabilityDefinition.ParamDef p : def.getParams()) {
                properties.put(p.getName(), paramSchema(p));
                if (p.isRequired()) {
                    required.add(p.getName());
                }
            }
        }
        properties.put("confidence", Map.of(
                "type", "number", "minimum", 0, "maximum", 1,
                "description", "选择该工具的置信度（0~1），拿不准时给低值"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    /** 单参数定义 → JSON Schema 片段（daterange 以自然语言时间表达式表达） */
    private static Map<String, Object> paramSchema(CapabilityDefinition.ParamDef p) {
        Map<String, Object> m = new LinkedHashMap<>();
        switch (p.getType() == null ? "string" : p.getType()) {
            case "int" -> m.put("type", "integer");
            case "number" -> m.put("type", "number");
            case "boolean" -> m.put("type", "boolean");
            case "array<string>" -> {
                m.put("type", "array");
                m.put("items", Map.of("type", "string"));
                if (p.getMaxItems() != null) m.put("maxItems", p.getMaxItems());
            }
            case "daterange" -> {
                m.put("type", "string");
                m.put("description", joinDesc(p.getDescription(),
                        "自然语言时间表达式（如「近7天」「昨天」），不要换算为绝对日期"));
            }
            default -> m.put("type", "string");
        }
        if (!"daterange".equals(p.getType()) && p.getDescription() != null) {
            m.put("description", p.getDescription());
        }
        if (p.getEnumValues() != null && !p.getEnumValues().isEmpty()) {
            m.put("enum", p.getEnumValues());
        }
        return m;
    }

    /** 拼接参数描述（避免覆盖 daterange 的固定提示） */
    private static String joinDesc(String base, String fixed) {
        return base == null || base.isBlank() ? fixed : base + "；" + fixed;
    }

    /** 结论生成系统 prompt：只能引用结果集数据，禁止编造数值 */
    public static final String CONCLUDE_SYSTEM = """
            你是车联网数据助手的结论生成模块。根据提供的数据表格，用简洁中文回答用户问题。
            红线（必须遵守）：
            1. 回答中的每个数值都必须来自提供的数据，禁止编造或推算未提供的数值。
            2. 数值保留与数据一致的精度与单位；里程单位 km，SOC 单位 %。
            3. 先给结论，再给关键数据支撑；3~6 句话以内，使用 Markdown。
            4. 如果数据为空，直接说明该条件下没有数据，并建议调整时间或范围。
            5. 语气专业、客观、书面化；禁用感叹号堆砌、拟人比喻与 emoji。
            """;

    // ==================== capability AI generation (capability factory) ====================

    /** GEN_SYSTEM: table schemas + user description -> strict CapabilityDefinition JSON */
    public static final String GEN_SYSTEM = """
            You are the capability factory of a connected-vehicle data platform.
            Given the user's requirement and the provided table schemas, design ONE data query capability and output strict JSON.
            Hard rules (must follow):
            1. Use ONLY the provided tables and columns. Never invent tables or columns.
            2. sql_template MUST contain the ACL placeholder ${acl_org_ids}, typically:
               t.vin_code IN (SELECT vin_code FROM basic_vehicle_info WHERE org_name IN (${acl_org_ids}))
               If the main table has no vin_code column, JOIN basic_vehicle_info first.
            3. sql_template MUST contain LIMIT (<= 500). SELECT * is forbidden.
            4. If the query has a time range, declare a daterange param named time_range and use ${time_from} / ${time_to} in SQL.
            5. Vehicle filter convention: AND (${vin_list_empty} OR t.vin_code IN (${vin_list})), with params vehicle (string, optional) and vin_list (array<string>, optional).
            6. Every ${placeholder} except acl_* must correspond to a declared param (a daterange param yields time_from/time_to; an array param x also yields x_empty).
            7. returns.columns must match the SQL output columns one-to-one; semantic in time/category/metric/geo_lng/geo_lat/id.
            8. chart_hint in table/bar/line/pie/area/scatter/map/metric_card.
            9. row_filter_policy = by_org; scopes like vehicle.<domain>.read; id in snake_case English, unique, not colliding with existing ids.
            10. display / description / aliases / sample_questions MUST be Simplified Chinese.
            Output JSON only, no explanation.
            """;

    /** GEN user prompt: existing ids (anti-collision) + table schemas + requirement */
    public static String genUserPrompt(String description, String schemaText, List<String> existingIds) {
        return "Existing capability ids (do not reuse): " + String.join(", ", existingIds)
                + "\n\nAvailable table schemas:\n" + schemaText
                + "\nUser requirement:\n" + description;
    }

    /** GEN output JSON Schema (snake_case fields, same source as yaml/Java model) */
    public static String genOutputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("id", Map.of("type", "string"));
        props.put("display", Map.of("type", "string"));
        props.put("description", Map.of("type", "string"));
        props.put("aliases", Map.of("type", "array", "items", Map.of("type", "string")));
        props.put("domain", Map.of("type", "string"));
        props.put("params", Map.of("type", "array", "items", Map.of(
                "type", "object",
                "properties", Map.of(
                        "name", Map.of("type", "string"),
                        "type", Map.of("type", "string", "enum", List.of("string", "int", "number", "boolean", "daterange", "array<string>")),
                        "required", Map.of("type", "boolean"),
                        "description", Map.of("type", "string"),
                        "max_span_days", Map.of("type", "integer"),
                        "max_items", Map.of("type", "integer"),
                        "default_value", Map.of("type", "string")),
                "required", List.of("name", "type"))));
        props.put("returns", Map.of(
                "type", "object",
                "properties", Map.of(
                        "shape", Map.of("type", "string", "enum", List.of("table", "metric", "geo")),
                        "columns", Map.of("type", "array", "items", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "name", Map.of("type", "string"),
                                        "semantic", Map.of("type", "string", "enum", List.of("time", "category", "metric", "geo_lng", "geo_lat", "id")),
                                        "display", Map.of("type", "string"),
                                        "unit", Map.of("type", "string"),
                                        "scale", Map.of("type", "integer")),
                                "required", List.of("name", "semantic")))),
                "required", List.of("columns")));
        props.put("chart_hint", Map.of("type", "string"));
        props.put("source_tables", Map.of("type", "array", "items", Map.of("type", "string")));
        props.put("limits", Map.of("type", "object"));
        props.put("scopes", Map.of("type", "array", "items", Map.of("type", "string")));
        props.put("row_filter_policy", Map.of("type", "string", "enum", List.of("by_org", "by_fleet", "by_vin")));
        props.put("sample_questions", Map.of("type", "array", "items", Map.of("type", "string")));
        props.put("sql_template", Map.of("type", "string"));
        schema.put("properties", props);
        schema.put("required", List.of("id", "display", "description", "domain", "params", "returns",
                "chart_hint", "source_tables", "scopes", "row_filter_policy", "sample_questions", "sql_template"));
        schema.put("additionalProperties", true);
        try {
            return MAPPER.writeValueAsString(schema);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
