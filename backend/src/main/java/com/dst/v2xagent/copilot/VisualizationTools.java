package com.dst.v2xagent.copilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 可视化与计算工具集（每次运行构造一个实例）。
 * generate_visualization：统一 UI Schema（type/visualizationType/title/renderer/data/spec），
 * 前端 VisualizationRenderer 按 renderer 分发 echarts/mermaid/g6/table/map。
 * calculate：确定性数值计算，避免模型口算出错。
 */
public class VisualizationTools {

    private final ToolResultBridge bridge;
    private final ObjectMapper mapper = new ObjectMapper();

    public VisualizationTools(ToolResultBridge bridge) {
        this.bridge = bridge;
    }

    /** 统一可视化工具 */
    @Tool(name = "generate_visualization",
            description = "生成可视化并直接展示给用户。图表类(line_chart/bar_chart/pie_chart/scatter_chart/radar_chart/gauge_chart/heatmap)传 columns+rows；流程图类(flowchart/sequence_diagram/er_diagram/mindmap/state_diagram/class_diagram)传 mermaid 代码；拓扑类(topology)传 nodes+edges；表格(table)传 columns+rows；地图(geo_map)传 points[{name,lng,lat,value}]")
    public String generateVisualization(
            @ToolParam(name = "visualization_type", required = true,
                    description = "line_chart/bar_chart/pie_chart/scatter_chart/radar_chart/gauge_chart/heatmap/flowchart/sequence_diagram/er_diagram/mindmap/state_diagram/class_diagram/topology/table/geo_map") String visualizationType,
            @ToolParam(name = "title", required = true, description = "可视化标题") String title,
            @ToolParam(name = "caption", required = true,
                    description = "一句话注明这张图展示什么、回答什么问题，例如「展示近 7 天各类告警占比，用于识别高频告警类型」") String caption,
            @ToolParam(name = "data", required = true,
                    description = "JSON 字符串。图表/表格: {columns:[],rows:[[]]}；mermaid 类: {code:...}；topology: {nodes:[{id,label}],edges:[{source,target,label}]}；geo_map: {points:[{name,lng,lat,value}]}") String data) {
        try {
            JsonNode node = mapper.readTree(data);
            String renderer = rendererOf(visualizationType);
            if (renderer == null) {
                return msg("bad_type", "不支持的可视化类型：" + visualizationType);
            }
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "visualization");
            schema.put("visualizationType", visualizationType);
            schema.put("title", title);
            schema.put("caption", caption);
            schema.put("renderer", renderer);
            schema.put("data", node);
            schema.put("spec", "echarts".equals(renderer)
                    ? EchartsSpecBuilder.build(visualizationType, title, node) : null);
            bridge.emitVisSpec(schema);
            return msg("ok", "可视化已展示：" + title);
        } catch (Exception e) {
            return msg("bad_data", "data 不是合法 JSON：" + e.getMessage());
        }
    }

    /** 类型 -> 渲染器映射 */
    private static String rendererOf(String type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case "line_chart", "bar_chart", "pie_chart", "scatter_chart",
                 "radar_chart", "gauge_chart", "heatmap" -> "echarts";
            case "flowchart", "sequence_diagram", "er_diagram",
                 "mindmap", "state_diagram", "class_diagram" -> "mermaid";
            case "topology" -> "g6";
            case "table" -> "table";
            case "geo_map" -> "map";
            default -> null;
        };
    }

    /** 数值计算工具：避免模型口算出错 */
    @Tool(name = "calculate",
            description = "对一组数字做确定性计算：sum/avg/max/min/count/ratio(比值)/growth(增长率)")
    public String calculate(
            @ToolParam(name = "operation", required = true,
                    description = "sum/avg/max/min/count/ratio/growth；ratio=第一数/第二数，growth=(第二数-第一数)/第一数") String operation,
            @ToolParam(name = "numbers", required = true, description = "数字列表，如 [120, 98, 76]") List<Double> numbers) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (numbers == null || numbers.isEmpty()) {
            return msg("bad_input", "numbers 不能为空");
        }
        Double result = switch (operation) {
            case "sum" -> numbers.stream().mapToDouble(Double::doubleValue).sum();
            case "avg" -> numbers.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            case "max" -> numbers.stream().mapToDouble(Double::doubleValue).max().orElse(0);
            case "min" -> numbers.stream().mapToDouble(Double::doubleValue).min().orElse(0);
            case "count" -> (double) numbers.size();
            case "ratio" -> numbers.size() >= 2 && numbers.get(1) != 0
                    ? round2(numbers.get(0) / numbers.get(1)) : null;
            case "growth" -> numbers.size() >= 2 && numbers.get(0) != 0
                    ? round2((numbers.get(1) - numbers.get(0)) / numbers.get(0)) : null;
            default -> null;
        };
        if (result == null) {
            return msg("bad_operation", "不支持的运算或参数不足：" + operation);
        }
        out.put("status", "ok");
        out.put("operation", operation);
        out.put("result", result);
        return toJson(out);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private String msg(String status, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", status);
        out.put("message", message);
        return toJson(out);
    }

    private String toJson(Object v) {
        try {
            return mapper.writeValueAsString(v);
        } catch (Exception e) {
            return String.valueOf(v);
        }
    }
}
