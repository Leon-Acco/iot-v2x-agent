package com.dst.v2xagent.visualization;

import com.dst.v2xagent.capability.model.CapabilityDefinition;
import com.dst.v2xagent.capability.model.TableResult;
import com.dst.v2xagent.semantic.model.SemanticResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 可视化规划器：Semantic Result / TableResult → ChartSpec（规则驱动，不调模型）
 * 规则：1 指标 → KPI；指标 + 时间 → Line；指标 + 类目 → Bar；geo → Map；明细/多列 → Table。
 * 用户切换图表类型走 replan：同一份数据重出契约，不重调 Agent / 不重查数据。
 */
@Component
public class VisualizationPlanner {

    /** 语义结果 → ChartSpec */
    public ChartSpec plan(SemanticResult result, String resultId) {
        String type = chooseType(result);
        List<String> columns = new ArrayList<>(result.dimensions());
        columns.add("value");
        List<List<Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : result.rows()) {
            List<Object> r = new ArrayList<>();
            for (String dim : result.dimensions()) {
                r.add(row.get(dim));
            }
            r.add(row.get("value"));
            rows.add(r);
        }
        Map<String, String> encoding = new LinkedHashMap<>();
        if (!result.dimensions().isEmpty()) {
            encoding.put("x", result.dimensions().get(0));
        }
        encoding.put("y", "value");
        return new ChartSpec(type, result.metricName(), new ChartSpec.DataBlock(resultId, columns, rows), encoding);
    }

    /** 表结果 → ChartSpec（ResultStore 回放用） */
    public ChartSpec planTable(TableResult table, String title, String resultId) {
        String type = chooseTableType(table);
        return toSpec(table, title, resultId, type);
    }

    /** 切换图表类型：同一份数据重出契约（不重查数据） */
    public ChartSpec replan(TableResult table, String title, String resultId, String targetType) {
        String type = isCompatible(table, targetType) ? targetType : chooseTableType(table);
        return toSpec(table, title, resultId, type);
    }

    /** 类型选择（语义结果） */
    private String chooseType(SemanticResult result) {
        if (result.dimensions().isEmpty()) {
            return "kpi";
        }
        String firstDim = result.dimensions().get(0);
        if ("day".equals(firstDim) || "hour".equals(firstDim)) {
            return "line";
        }
        if ("province".equals(firstDim) || "city".equals(firstDim) || "district".equals(firstDim)) {
            return "map";
        }
        return result.rows().size() > 20 ? "table" : "bar";
    }

    /** 类型选择（表结果，按列语义推断） */
    private String chooseTableType(TableResult table) {
        List<CapabilityDefinition.ColumnDef> cols = table.columns();
        long metrics = cols.stream().filter(c -> "metric".equals(c.getSemantic())).count();
        boolean hasTime = cols.stream().anyMatch(c -> "time".equals(c.getSemantic()));
        boolean hasGeo = cols.stream().anyMatch(c -> "geo_lng".equals(c.getSemantic()));
        long categories = cols.stream().filter(c -> "category".equals(c.getSemantic()) || "id".equals(c.getSemantic())).count();
        if (hasGeo) {
            return "map";
        }
        if (metrics == 1 && categories == 0) {
            return "kpi";
        }
        if (metrics == 1 && hasTime) {
            return "line";
        }
        if (metrics == 1 && categories == 1 && table.rows().size() <= 20) {
            return "bar";
        }
        return "table";
    }

    /** 兼容性校验：目标类型与数据结构是否匹配（不匹配回退推荐类型） */
    private boolean isCompatible(TableResult table, String targetType) {
        if (targetType == null) {
            return false;
        }
        return switch (targetType) {
            case "kpi" -> table.columns().stream().filter(c -> "metric".equals(c.getSemantic())).count() >= 1;
            case "line" -> table.columns().stream().anyMatch(c -> "time".equals(c.getSemantic()));
            case "map" -> table.columns().stream().anyMatch(c -> "geo_lng".equals(c.getSemantic()));
            case "bar", "pie", "table" -> true;
            default -> false;
        };
    }

    private ChartSpec toSpec(TableResult table, String title, String resultId, String type) {
        List<String> columns = table.columns().stream().map(CapabilityDefinition.ColumnDef::getName).toList();
        List<List<Object>> rows = new ArrayList<>();
        for (Object[] row : table.rows()) {
            List<Object> r = new ArrayList<>();
            for (Object cell : row) {
                r.add(cell);
            }
            rows.add(r);
        }
        Map<String, String> encoding = new LinkedHashMap<>();
        String x = table.columns().stream()
                .filter(c -> "time".equals(c.getSemantic()) || "category".equals(c.getSemantic()))
                .map(CapabilityDefinition.ColumnDef::getName).findFirst().orElse(null);
        String y = table.columns().stream().filter(c -> "metric".equals(c.getSemantic()))
                .map(CapabilityDefinition.ColumnDef::getName).findFirst().orElse(null);
        if (x != null) {
            encoding.put("x", x);
        }
        if (y != null) {
            encoding.put("y", y);
        }
        return new ChartSpec(type, title, new ChartSpec.DataBlock(resultId, columns, rows), encoding);
    }
}
