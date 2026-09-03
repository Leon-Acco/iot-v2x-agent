package com.dst.v2xagent.visualization;

import com.dst.v2xagent.capability.model.CapabilityDefinition;
import com.dst.v2xagent.capability.model.TableResult;
import com.dst.v2xagent.semantic.model.SemanticResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 可视化规划器单元测试：类型选择规则 + replan 兼容性
 */
class VisualizationPlannerTest {

    private final VisualizationPlanner planner = new VisualizationPlanner();

    private SemanticResult semantic(List<String> dims, List<Map<String, Object>> rows) {
        return new SemanticResult("m.test", "测试指标", "count", dims,
                dims.isEmpty() ? 42.0 : null, rows, null);
    }

    @Test
    void typeRules() {
        // 单指标 -> KPI
        assertEquals("kpi", planner.plan(semantic(List.of(), List.of()), "r1").type());
        // 时间维度 -> Line
        assertEquals("line", planner.plan(semantic(List.of("day"),
                List.of(Map.of("day", "2026-09-01", "value", 1.0))), "r1").type());
        // 地理维度 -> Map
        assertEquals("map", planner.plan(semantic(List.of("province"),
                List.of(Map.of("province", "广东省", "value", 1.0))), "r1").type());
        // 少量类目 -> Bar
        assertEquals("bar", planner.plan(semantic(List.of("alarm_type"),
                List.of(Map.of("alarm_type", "超速", "value", 1.0))), "r1").type());
    }

    @Test
    void replanRespectsCompatibility() {
        CapabilityDefinition.ColumnDef time = new CapabilityDefinition.ColumnDef();
        time.setName("d");
        time.setSemantic("time");
        CapabilityDefinition.ColumnDef metric = new CapabilityDefinition.ColumnDef();
        metric.setName("v");
        metric.setSemantic("metric");
        TableResult table = new TableResult(List.of(time, metric),
                java.util.Collections.singletonList(new Object[]{"2026-09-01", 1.0}), 1, false, null, null);

        // 时间列存在：line 兼容
        assertEquals("line", planner.replan(table, "t", "r1", "line").type());
        // map 不兼容（无 geo 列）回退推荐类型
        assertNotEquals("map", planner.replan(table, "t", "r1", "map").type());
        // table 永远兼容
        assertEquals("table", planner.replan(table, "t", "r1", "table").type());
    }
}
