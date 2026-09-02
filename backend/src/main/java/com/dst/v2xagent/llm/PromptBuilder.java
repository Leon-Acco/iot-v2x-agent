package com.dst.v2xagent.llm;

import com.dst.v2xagent.capability.model.CapabilityDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 抽取 prompt 与输出 Schema 构建器（真实模型模式）
 * Schema 由候选 capability 元数据生成，与 Java 校验器、管理后台表单同源。
 */
public final class PromptBuilder {

    private PromptBuilder() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 抽取系统 prompt：角色 + 红线 + 输出契约（稳定前缀，利于 Prompt Caching） */
    public static final String EXTRACT_SYSTEM = """
            你是车联网数据助手的意图理解模块。你的唯一任务：把用户问题映射到一个 capability（数据能力），并抽取参数。
            红线（必须遵守）：
            1. 绝不输出 SQL；绝不编造 capability_id、字段名或参数值。
            2. capability_id 只能从候选列表中选择。
            3. 时间一律输出自然语言表达式（如「近7天」「昨天」），不要自己换算绝对日期。
            4. 车辆标识（车牌/VIN）填到 vehicle 参数；不要把权限相关字段（如 acl_*）放入参数。
            5. 拿不准时降低 confidence 并把候选项放入 alternatives。
            6. 如果用户问题与所有候选能力都无关（闲聊、问候、常识问答、与车辆数据无关），capability_id 必须输出 null，confidence 输出 0，不得勉强选择。
            只输出 JSON，不要输出任何解释。
            """;

    /** 抽取用户 prompt：候选目录（裁剪后）+ 用户问题 */
    public static String extractUserPrompt(String question, List<CapabilityDefinition> candidates) {
        StringBuilder sb = new StringBuilder("候选 capability 列表：\n");
        for (CapabilityDefinition def : candidates) {
            sb.append("- id: ").append(def.getId())
                    .append("，名称: ").append(def.getDisplay())
                    .append("，说明: ").append(def.getDescription())
                    .append("，参数: ");
            List<String> ps = new ArrayList<>();
            for (CapabilityDefinition.ParamDef p : def.getParams()) {
                ps.add(p.getName() + "(" + p.getType() + (p.isRequired() ? ",必填" : ",可选")
                        + (p.getEnumValues() != null ? ",枚举" + p.getEnumValues() : "") + ")");
            }
            sb.append(String.join("、", ps)).append("\n");
        }
        sb.append("\n用户问题：").append(question);
        return sb.toString();
    }

    /** 抽取输出 JSON Schema（强约束；不存在 sql 字段） */
    public static String extractOutputSchema(List<CapabilityDefinition> candidates) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        java.util.List<Object> idEnum = new java.util.ArrayList<>(
                candidates.stream().map(CapabilityDefinition::getId).map(x -> (Object) x).toList());
        idEnum.add(null); // 允许拒答：闲聊/无关问题不强制路由
        props.put("capability_id", Map.of("type", java.util.List.of("string", "null"),
                "enum", idEnum));
        props.put("confidence", Map.of("type", "number", "minimum", 0, "maximum", 1));
        props.put("params", Map.of("type", "object"));
        props.put("missing", Map.of("type", "array", "items", Map.of("type", "string")));
        props.put("alternatives", Map.of("type", "array", "items", Map.of("type", "string")));
        schema.put("properties", props);
        schema.put("required", List.of("capability_id", "confidence", "params"));
        schema.put("additionalProperties", false);
        try {
            return MAPPER.writeValueAsString(schema);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 结论生成系统 prompt：只能引用结果集数据，禁止编造数值 */
    public static final String CONCLUDE_SYSTEM = """
            你是车联网数据助手的结论生成模块。根据提供的数据表格，用简洁中文回答用户问题。
            红线（必须遵守）：
            1. 回答中的每个数值都必须来自提供的数据，禁止编造或推算未提供的数值。
            2. 数值保留与数据一致的精度与单位；里程单位 km，SOC 单位 %。
            3. 先给结论，再给关键数据支撑；3~6 句话以内，使用 Markdown。
            4. 如果数据为空，直接说明该条件下没有数据，并建议调整时间或范围。
            """;
}
