package com.dst.v2xagent.capability;

import com.dst.v2xagent.capability.model.CapabilityDefinition;
import com.dst.v2xagent.capability.model.TableResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RenderChartTool：后端只出 ChartSpec（Vega-Lite），不出图片。
 * 决策顺序：chart_hint → 列语义类型推导 → 行数/基数修正。
 * 前端切图走 chartAlternatives 白名单，不重新请求模型、不重新查数据。
 */
@Component
public class RenderChartTool {

    /** ChartSpec 输出 */
    public record ChartSpec(
            String chartType,
            Map<String, Object> spec,
            List<String> chartAlternatives,
            List<String> notes
    ) {}

    /**
     * 根据结果列结构生成 Vega-Lite spec。
     * @param def    capability 定义（chart_hint / returns.columns）
     * @param result 查询结果
     */
    public ChartSpec render(CapabilityDefinition def, TableResult result) {
        List<CapabilityDefinition.ColumnDef> cols = result.columns();
        List<CapabilityDefinition.ColumnDef> metrics = cols.stream().filter(c -> "metric".equals(c.getSemantic())).toList();
        List<CapabilityDefinition.ColumnDef> times = cols.stream().filter(c -> "time".equals(c.getSemantic())).toList();
        List<CapabilityDefinition.ColumnDef> categories = cols.stream().filter(c -> "category".equals(c.getSemantic())).toList();
        boolean hasGeo = cols.stream().anyMatch(c -> "geo_lng".equals(c.getSemantic()))
                && cols.stream().anyMatch(c -> "geo_lat".equals(c.getSemantic()));

        String chartType;
        List<String> alternatives = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        // 多指标时取第一个指标出图（其余指标在表格展示）
        CapabilityDefinition.ColumnDef primaryMetric = metrics.isEmpty() ? null : metrics.get(0);

        if (hasGeo) {
            chartType = "map";
            alternatives.addAll(List.of("map", "table"));
        } else if (primaryMetric != null && times.isEmpty() && categories.isEmpty()) {
            chartType = "metric_card";
            alternatives.addAll(List.of("metric_card", "table"));
        } else if (primaryMetric != null && times.size() == 1) {
            // 时间序列：点数 < 3 降级柱状
            chartType = result.rowCount() < 3 ? "bar" : "line";
            alternatives.addAll(List.of("line", "bar", "area", "table"));
        } else if (primaryMetric != null && !categories.isEmpty()) {
            CapabilityDefinition.ColumnDef cat = categories.get(0);
            long distinct = distinctCount(result, indexOf(cols, cat));
            if (distinct > 20) {
                notes.add("类别超过 20 项，已按指标取 Top 20 展示");
            }
            chartType = "bar";
            alternatives.addAll(List.of("bar", "pie", "line", "table"));
        } else {
            // 明细多列：不强行出图
            chartType = "table";
            alternatives.add("table");
        }

        // chart_hint 强提示：仅在推导结果为 table（明细场景）但列结构支持出图时生效
        if ("table".equals(chartType) && def.getChartHint() != null && !"table".equals(def.getChartHint())
                && primaryMetric != null && (!categories.isEmpty() || !times.isEmpty())) {
            chartType = def.getChartHint();
            alternatives = new ArrayList<>(List.of(chartType, "table"));
        }

        Map<String, Object> spec = buildVegaLiteSpec(chartType, cols, result);
        return new ChartSpec(chartType, spec, alternatives, notes);
    }

    /** 构造 Vega-Lite spec（data 由前端注入同一份 TOOL_CALL_RESULT 数据） */
    private Map<String, Object> buildVegaLiteSpec(String chartType, List<CapabilityDefinition.ColumnDef> cols, TableResult result) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("$schema", "https://vega.github.io/schema/vega-lite/v5.json");
        spec.put("data", Map.of("name", "result"));
        spec.put("width", "container");
        spec.put("height", 280);

        CapabilityDefinition.ColumnDef metric = cols.stream().filter(c -> "metric".equals(c.getSemantic())).findFirst().orElse(null);
        CapabilityDefinition.ColumnDef time = cols.stream().filter(c -> "time".equals(c.getSemantic())).findFirst().orElse(null);
        CapabilityDefinition.ColumnDef category = cols.stream().filter(c -> "category".equals(c.getSemantic())).findFirst().orElse(null);

        Map<String, Object> encoding = new LinkedHashMap<>();
        switch (chartType) {
            case "line", "area" -> {
                spec.put("mark", Map.of("type", chartType, "point", true, "tooltip", true));
                if (time != null) encoding.put("x", field(time, "temporal"));
                if (metric != null) encoding.put("y", field(metric, "quantitative"));
                if (category != null) encoding.put("color", field(category, "nominal"));
            }
            case "bar" -> {
                spec.put("mark", Map.of("type", "bar", "tooltip", true));
                if (time != null) {
                    encoding.put("x", field(time, "temporal"));
                } else if (category != null) {
                    Map<String, Object> x = field(category, "nominal");
                    x.put("sort", "-y");
                    encoding.put("x", x);
                }
                if (metric != null) encoding.put("y", field(metric, "quantitative"));
            }
            case "pie" -> {
                spec.put("mark", Map.of("type", "arc", "tooltip", true));
                if (metric != null) encoding.put("theta", field(metric, "quantitative"));
                if (category != null) encoding.put("color", field(category, "nominal"));
            }
            default -> {
                // metric_card / map / table：前端自渲染，spec 仅携带列信息
                spec.put("mark", "point");
            }
        }
        spec.put("encoding", encoding);
        return spec;
    }

    private Map<String, Object> field(CapabilityDefinition.ColumnDef col, String type) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("field", col.getName());
        f.put("type", type);
        String title = col.getDisplay() != null ? col.getDisplay() : col.getName();
        if (col.getUnit() != null) title += "（" + col.getUnit() + "）";
        f.put("title", title);
        return f;
    }

    private int indexOf(List<CapabilityDefinition.ColumnDef> cols, CapabilityDefinition.ColumnDef col) {
        for (int i = 0; i < cols.size(); i++) if (cols.get(i).getName().equals(col.getName())) return i;
        return 0;
    }

    private long distinctCount(TableResult result, int colIndex) {
        return result.rows().stream().map(r -> String.valueOf(r[colIndex])).distinct().count();
    }
}
