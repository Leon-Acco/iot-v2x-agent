package com.dst.v2xagent.semantic;

import com.dst.v2xagent.common.ApiException;
import com.dst.v2xagent.semantic.model.DimensionDefinition;
import com.dst.v2xagent.semantic.model.MetricDefinition;
import com.dst.v2xagent.semantic.model.SemanticQuery;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 语义层单元测试：yaml 解析 + Query Planner 维度校验
 */
class SemanticLayerTest {

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    /** metrics.yaml 必须可解析且每个指标都绑定 capability */
    @Test
    void metricsYamlParses() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/semantic/metrics.yaml")) {
            assertNotNull(in, "semantic/metrics.yaml 必须存在");
            List<MetricDefinition> metrics = yamlMapper.readValue(in,
                    yamlMapper.getTypeFactory().constructCollectionType(List.class, MetricDefinition.class));
            assertFalse(metrics.isEmpty());
            for (MetricDefinition m : metrics) {
                assertNotNull(m.getId());
                assertNotNull(m.getCapability(), "指标 " + m.getId() + " 必须绑定 capability");
            }
        }
    }

    /** dimensions.yaml 必须可解析 */
    @Test
    void dimensionsYamlParses() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/semantic/dimensions.yaml")) {
            assertNotNull(in);
            List<DimensionDefinition> dims = yamlMapper.readValue(in,
                    yamlMapper.getTypeFactory().constructCollectionType(List.class, DimensionDefinition.class));
            assertTrue(dims.size() >= 5);
        }
    }

    /** Planner：合法维度出计划，非法维度拒绝 */
    @Test
    void plannerValidatesDimensions() {
        SemanticRegistry registry = new SemanticRegistry(null);
        MetricDefinition metric = new MetricDefinition();
        metric.setId("alarm.count");
        metric.setCapability("alarm_count_by_type");
        metric.setValueColumn("alarm_count");
        metric.setDimensions(List.of("alarm_type"));
        metric.setDimensionColumns(Map.of("alarm_type", "alarm_type"));
        @SuppressWarnings("unchecked")
        Map<String, MetricDefinition> metrics =
                (Map<String, MetricDefinition>) ReflectionTestUtils.getField(registry, "metrics");
        assertNotNull(metrics);
        metrics.put(metric.getId(), metric);

        SemanticQueryPlanner planner = new SemanticQueryPlanner(registry);

        SemanticQueryPlanner.QueryPlan ok = planner.plan(new SemanticQuery(
                "alarm.count", List.of("alarm_type"), Map.of(), "近7天"));
        assertEquals("alarm_count_by_type", ok.capabilityId());
        assertEquals("近7天", ok.params().get("time_range"));

        ApiException ex = assertThrows(ApiException.class, () -> planner.plan(
                new SemanticQuery("alarm.count", List.of("province"), Map.of(), null)));
        assertEquals("PARAM_INVALID", ex.errorCode());

        assertThrows(ApiException.class, () -> planner.plan(
                new SemanticQuery("not.exists", List.of(), Map.of(), null)));
    }
}
