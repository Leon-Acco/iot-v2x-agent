package com.dst.v2xagent.llm;

import com.dst.v2xagent.capability.model.CapabilityDefinition;
import com.dst.v2xagent.runtime.spi.StructuredModelClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 能力 → function calling 工具定义生成 单元测试
 * 验证 ParamDef → JSON Schema 映射、confidence 注入与 reject_question 兜底工具
 */
class ToolSpecBuilderTest {

    /** 构造带各类参数的测试能力 */
    private CapabilityDefinition def() {
        CapabilityDefinition d = new CapabilityDefinition();
        d.setId("alarm_count_by_type");
        d.setDisplay("告警类型统计");
        d.setDescription("按告警类型聚合计数");
        d.setAliases(List.of("告警统计", "哪种告警最多"));

        CapabilityDefinition.ParamDef timeRange = new CapabilityDefinition.ParamDef();
        timeRange.setName("time_range");
        timeRange.setType("daterange");
        timeRange.setRequired(true);

        CapabilityDefinition.ParamDef vehicle = new CapabilityDefinition.ParamDef();
        vehicle.setName("vehicle");
        vehicle.setType("string");

        CapabilityDefinition.ParamDef alarmType = new CapabilityDefinition.ParamDef();
        alarmType.setName("alarm_type");
        alarmType.setType("string");
        alarmType.setEnumValues(List.of("超速", "急刹"));

        CapabilityDefinition.ParamDef vinList = new CapabilityDefinition.ParamDef();
        vinList.setName("vin_list");
        vinList.setType("array<string>");
        vinList.setMaxItems(50);

        d.setParams(List.of(timeRange, vehicle, alarmType, vinList));
        return d;
    }

    @Test
    void 每个能力一个工具_末尾追加拒答兜底() {
        List<StructuredModelClient.ToolSpec> tools = PromptBuilder.buildToolSpecs(List.of(def()));
        assertEquals(2, tools.size());
        assertEquals("alarm_count_by_type", tools.get(0).name());
        assertEquals("reject_question", tools.get(1).name());
    }

    @Test
    void 工具描述含名称说明与别名() {
        String desc = PromptBuilder.buildToolSpecs(List.of(def())).get(0).description();
        assertTrue(desc.contains("告警类型统计"));
        assertTrue(desc.contains("按告警类型聚合计数"));
        assertTrue(desc.contains("告警统计"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void 参数类型映射与必填汇总() {
        Map<String, Object> params = PromptBuilder.buildToolSpecs(List.of(def())).get(0).parameters();
        Map<String, Object> props = (Map<String, Object>) params.get("properties");

        // daterange → string（自然语言时间表达式）
        Map<String, Object> tr = (Map<String, Object>) props.get("time_range");
        assertEquals("string", tr.get("type"));
        assertTrue(String.valueOf(tr.get("description")).contains("自然语言时间表达式"));

        // enum 透传
        Map<String, Object> at = (Map<String, Object>) props.get("alarm_type");
        assertEquals(List.of("超速", "急刹"), at.get("enum"));

        // array<string> → array + items + maxItems
        Map<String, Object> vl = (Map<String, Object>) props.get("vin_list");
        assertEquals("array", vl.get("type"));
        assertEquals(50, vl.get("maxItems"));

        // confidence 注入
        Map<String, Object> conf = (Map<String, Object>) props.get("confidence");
        assertNotNull(conf);
        assertEquals(0, conf.get("minimum"));

        // 必填只含 time_range（confidence 不强制）
        assertEquals(List.of("time_range"), params.get("required"));
    }
}
