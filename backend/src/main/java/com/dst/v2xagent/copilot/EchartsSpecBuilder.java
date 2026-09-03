package com.dst.v2xagent.copilot;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ECharts option 确定性构建器：把 {columns, rows} 数据映射为图表 option。
 * 模型只传数据与类型，option 由后端生成。
 */
public final class EchartsSpecBuilder {

    private EchartsSpecBuilder() {}

    /** 按类型构建 option；不支持的类型返回 null */
    public static Map<String, Object> build(String type, String title, JsonNode data) {
        List<String> columns = textList(data.get("columns"));
        List<List<Object>> rows = rowsOf(data.get("rows"));
        if (rows.isEmpty()) {
            return null;
        }
        return switch (type) {
            case "line_chart" -> cartesian(title, columns, rows, "line");
            case "bar_chart" -> cartesian(title, columns, rows, "bar");
            case "pie_chart" -> pie(title, rows);
            case "scatter_chart" -> scatter(title, rows);
            case "radar_chart" -> radar(title, rows);
            case "gauge_chart" -> gauge(title, columns, rows);
            case "heatmap" -> heatmap(title, rows);
            default -> null;
        };
    }

    /** 折线/柱状：col0=x，其余列为系列 */
    private static Map<String, Object> cartesian(String title,
                                                 List<String> columns, List<List<Object>> rows, String seriesType) {
        List<Object> x = new ArrayList<>();
        for (List<Object> r : rows) {
            x.add(cell(r, 0));
        }
        List<Object> series = new ArrayList<>();
        for (int c = 1; c < width(columns, rows); c++) {
            List<Object> vals = new ArrayList<>();
            for (List<Object> r : rows) {
                vals.add(num(cell(r, c)));
            }
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("name", c < columns.size() ? columns.get(c) : "series" + c);
            s.put("type", seriesType);
            s.put("data", vals);
            if ("line".equals(seriesType)) {
                s.put("smooth", true);
            }
            series.add(s);
        }
        Map<String, Object> option = base(title);
        option.put("xAxis", Map.of("type", "category", "data", x));
        option.put("yAxis", Map.of("type", "value"));
        option.put("series", series);
        return option;
    }

    /** 饼图：col0=名，col1=值 */
    private static Map<String, Object> pie(String title, List<List<Object>> rows) {
        List<Object> data = new ArrayList<>();
        for (List<Object> r : rows) {
            data.add(Map.of("name", String.valueOf(cell(r, 0)), "value", num(cell(r, 1))));
        }
        Map<String, Object> option = base(title);
        option.put("series", List.of(Map.of(
                "type", "pie", "radius", List.of("40%", "70%"), "data", data)));
        return option;
    }

    /** 散点：col0=x, col1=y */
    private static Map<String, Object> scatter(String title, List<List<Object>> rows) {
        List<Object> data = new ArrayList<>();
        for (List<Object> r : rows) {
            data.add(List.of(num(cell(r, 0)), num(cell(r, 1))));
        }
        Map<String, Object> option = base(title);
        option.put("xAxis", Map.of("type", "value"));
        option.put("yAxis", Map.of("type", "value"));
        option.put("series", List.of(Map.of("type", "scatter", "symbolSize", 12, "data", data)));
        return option;
    }

    /** 雷达：col0=维度，col1=值 */
    private static Map<String, Object> radar(String title, List<List<Object>> rows) {
        List<Object> indicators = new ArrayList<>();
        List<Object> vals = new ArrayList<>();
        double max = 0;
        for (List<Object> r : rows) {
            double v = num(cell(r, 1)).doubleValue();
            if (v > max) {
                max = v;
            }
        }
        double cap = max <= 0 ? 100 : max * 1.2;
        for (List<Object> r : rows) {
            indicators.add(Map.of("name", String.valueOf(cell(r, 0)), "max", cap));
            vals.add(num(cell(r, 1)));
        }
        Map<String, Object> option = base(title);
        option.put("radar", Map.of("indicator", indicators));
        option.put("series", List.of(Map.of("type", "radar",
                "data", List.of(Map.of("value", vals, "name", title)))));
        return option;
    }

    /** 仪表盘：第一行取值 */
    private static Map<String, Object> gauge(String title, List<String> columns, List<List<Object>> rows) {
        Object v = num(cell(rows.get(0), columns.size() > 1 ? 1 : 0));
        Map<String, Object> option = base(title);
        option.put("series", List.of(Map.of(
                "type", "gauge", "progress", Map.of("show", true),
                "detail", Map.of("formatter", "{value}%"),
                "data", List.of(Map.of("value", v, "name", title)))));
        return option;
    }

    /** 热力：col0=x, col1=y, col2=值 */
    private static Map<String, Object> heatmap(String title, List<List<Object>> rows) {
        List<String> xs = new ArrayList<>();
        List<String> ys = new ArrayList<>();
        List<Object> data = new ArrayList<>();
        double max = 0;
        for (List<Object> r : rows) {
            String xv = String.valueOf(cell(r, 0));
            String yv = String.valueOf(cell(r, 1));
            if (xs.contains(xv) == false) {
                xs.add(xv);
            }
            if (ys.contains(yv) == false) {
                ys.add(yv);
            }
        }
        for (List<Object> r : rows) {
            double v = num(cell(r, 2)).doubleValue();
            if (v > max) {
                max = v;
            }
            data.add(List.of(xs.indexOf(String.valueOf(cell(r, 0))),
                    ys.indexOf(String.valueOf(cell(r, 1))), v));
        }
        Map<String, Object> option = base(title);
        option.put("xAxis", Map.of("type", "category", "data", xs));
        option.put("yAxis", Map.of("type", "category", "data", ys));
        option.put("visualMap", Map.of("min", 0, "max", max <= 0 ? 1 : max,
                "calculable", true, "orient", "horizontal", "left", "center", "bottom", "4%"));
        option.put("series", List.of(Map.of("type", "heatmap", "data", data,
                "label", Map.of("show", true))));
        return option;
    }

    private static Map<String, Object> base(String title) {
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("title", Map.of("text", title, "left", "center"));
        option.put("tooltip", Map.of("trigger", "axis"));
        option.put("legend", Map.of("bottom", 0));
        return option;
    }

    private static int width(List<String> columns, List<List<Object>> rows) {
        int w = columns.size();
        for (List<Object> r : rows) {
            if (r.size() > w) {
                w = r.size();
            }
        }
        return Math.max(w, 2);
    }

    private static Object cell(List<Object> row, int i) {
        return i < row.size() ? row.get(i) : null;
    }

    private static Number num(Object v) {
        if (v instanceof Number n) {
            return n;
        }
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (Exception e) {
            return 0;
        }
    }

    private static List<String> textList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(n -> out.add(n.asText()));
        }
        return out;
    }

    private static List<List<Object>> rowsOf(JsonNode node) {
        List<List<Object>> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(r -> {
                List<Object> row = new ArrayList<>();
                if (r.isArray()) {
                    r.forEach(c -> row.add(c.isNumber() ? c.numberValue() : c.asText()));
                }
                out.add(row);
            });
        }
        return out;
    }
}
