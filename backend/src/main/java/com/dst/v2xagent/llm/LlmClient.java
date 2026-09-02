package com.dst.v2xagent.llm;

import com.dst.v2xagent.capability.model.CapabilityDefinition;
import com.dst.v2xagent.capability.model.TableResult;
import com.dst.v2xagent.common.ApiException;
import com.dst.v2xagent.llm.mock.MockExtractor;
import com.dst.v2xagent.runtime.model.CapabilitySelection;
import com.dst.v2xagent.orchestration.OrchestrationExecutor;
import com.dst.v2xagent.runtime.spi.StreamingModelClient;
import com.dst.v2xagent.runtime.spi.StructuredModelClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * LLM 调用门面（模型路由 + 重试 + 护栏）
 * - 抽取节点用小模型（低温度、结构化输出、可重试 1 次）
 * - 结论节点用主模型（流式、不重试，预算不足时降级为 Java 模板摘要）
 * - mock 模式：纯规则抽取 + 模板结论（开发联调与降级路径）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmClient {

    private final LlmProperties props;
    private final MockExtractor mockExtractor;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 真实模式 SPI（mock 模式下为 null） */
    private final org.springframework.beans.factory.ObjectProvider<StructuredModelClient> structuredClient;
    private final org.springframework.beans.factory.ObjectProvider<StreamingModelClient> streamingClient;

    /**
     * 意图/槽位抽取：返回受 Schema 约束的 CapabilitySelection。
     * mock 模式走规则抽取；真实模式调用模型并做二次校验，失败可重试 1 次（降温度）。
     */
    public CapabilitySelection extract(String question, List<CapabilityDefinition> candidates) {
        if ("mock".equals(props.getActive())) {
            return mockExtractor.extract(question, candidates);
        }
        StructuredModelClient client = structuredClient.getIfAvailable();
        if (client == null) {
            log.warn("LLM 供应商不可用，降级为规则抽取");
            return mockExtractor.extract(question, candidates);
        }
        String schema = PromptBuilder.extractOutputSchema(candidates);
        String userPrompt = PromptBuilder.extractUserPrompt(question, candidates);
        LlmProperties.Stage stage = props.getExtract();

        Exception last = null;
        for (int attempt = 0; attempt <= stage.getMaxRetries(); attempt++) {
            try {
                double temperature = attempt == 0 ? stage.getTemperature() : 0;
                String json = client.generate(new StructuredModelClient.StructuredRequest(
                        PromptBuilder.EXTRACT_SYSTEM, userPrompt, schema, temperature, stage.getTimeoutMs()));
                CapabilitySelection selection = parseSelection(json);
                // 二次校验：capability_id 必须在候选内（失败关闭）
                String finalId = selection.capabilityId();
                if (finalId != null && candidates.stream().noneMatch(c -> c.getId().equals(finalId))) {
                    throw ApiException.schemaInvalid("模型产出了候选外的 capability_id: " + finalId);
                }
                return selection;
            } catch (Exception e) {
                last = e;
                log.warn("抽取第 {} 次失败: {}", attempt + 1, e.getMessage());
            }
        }
        if (last instanceof ApiException ae) throw ae;
        throw new ApiException("LLM_ERROR", "understand", true, "意图理解失败: " + (last == null ? "未知" : last.getMessage()));
    }

    /**
     * 结论生成（流式）。mock/降级路径用 Java 模板摘要——不能让措辞节点抹掉可信数据结果。
     */
    public void streamConclusion(String question, CapabilityDefinition def, TableResult table,
                                 String timeRangeDisplay, StreamingModelClient.TokenSink sink) {
        if ("mock".equals(props.getActive())) {
            emitTemplateConclusion(def, table, timeRangeDisplay, sink);
            return;
        }
        StreamingModelClient client = streamingClient.getIfAvailable();
        if (client == null) {
            emitTemplateConclusion(def, table, timeRangeDisplay, sink);
            return;
        }
        try {
            client.stream(new StreamingModelClient.StreamingRequest(
                    PromptBuilder.CONCLUDE_SYSTEM,
                    buildConcludeUserPrompt(question, def, table, timeRangeDisplay),
                    props.getConclude().getTemperature(),
                    props.getConclude().getTimeoutMs()), sink);
        } catch (Exception e) {
            log.warn("结论生成失败，降级为模板摘要: {}", e.getMessage());
            emitTemplateConclusion(def, table, timeRangeDisplay, sink);
        }
    }

    /** 模板结论（mock/降级路径）：只引用结果集数值，绝不算新数 */


    /**
     * 编排结论（P1）：多步数据汇总 + 缺失声明；mock 走模板摘要，真实模式走流式。
     */
    public void streamOrchestrationConclusion(String question, String templateDisplay,
                                              java.util.Map<String, OrchestrationExecutor.StepOutcome> steps,
                                              java.util.List<String> missing, String timeRangeDisplay,
                                              StreamingModelClient.TokenSink sink) {
        StreamingModelClient client = streamingClient.getIfAvailable();
        if ("mock".equals(props.getActive()) || client == null) {
            emitOrchestrationTemplateConclusion(templateDisplay, steps, missing, timeRangeDisplay, sink);
            return;
        }
        try {
            client.stream(new StreamingModelClient.StreamingRequest(
                    PromptBuilder.CONCLUDE_SYSTEM,
                    buildOrchestrationPrompt(question, templateDisplay, steps, missing, timeRangeDisplay),
                    props.getConclude().getTemperature(),
                    props.getConclude().getTimeoutMs()), sink);
        } catch (Exception e) {
            log.warn("编排结论生成失败，降级: {}", e.getMessage());
            emitOrchestrationTemplateConclusion(templateDisplay, steps, missing, timeRangeDisplay, sink);
        }
    }

    /** mock/降级路径：模板化摘要 */
    private void emitOrchestrationTemplateConclusion(String templateDisplay,
            java.util.Map<String, OrchestrationExecutor.StepOutcome> steps,
            java.util.List<String> missing, String timeRangeDisplay,
            StreamingModelClient.TokenSink sink) {
        StringBuilder sb = new StringBuilder();
        sb.append("已为你做多源取证分析「").append(templateDisplay).append("」");
        if (timeRangeDisplay != null) sb.append("，统计区间 ").append(timeRangeDisplay);
        sb.append("。\n\n");
        for (OrchestrationExecutor.StepOutcome step : steps.values()) {
            if (!step.ok()) continue;
            sb.append("【").append(step.display()).append("】共 ")
              .append(step.table().rowCount()).append(" 条记录");
            if (step.table().rowCount() > 0) {
                var cols = step.table().columns();
                var firstRow = step.table().rows().get(0);
                sb.append("，首条：");
                for (int c = 0; c < Math.min(3, cols.size()); c++) {
                    if (c > 0) sb.append("，");
                    sb.append(cols.get(c).getDisplay()).append(" ").append(firstRow[c]);
                }
            }
            sb.append("\n");
        }
        if (!missing.isEmpty()) {
            sb.append("\n注：未能获取「").append(String.join("、", missing))
              .append("」的数据（已降级，结论仅供参考）。");
        }
        sink.onToken(sb.toString());
    }

    /** 真实模式用户 prompt */
    private String buildOrchestrationPrompt(String question, String templateDisplay,
            java.util.Map<String, OrchestrationExecutor.StepOutcome> steps,
            java.util.List<String> missing, String timeRangeDisplay) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户问题：").append(question).append("\n");
        sb.append("分析主题：").append(templateDisplay).append("\n");
        if (timeRangeDisplay != null) sb.append("统计区间：").append(timeRangeDisplay).append("\n");
        sb.append("\n以下是各取证步骤的数据：\n");
        for (OrchestrationExecutor.StepOutcome step : steps.values()) {
            if (!step.ok()) continue;
            sb.append("【").append(step.display()).append("】共 ")
              .append(step.table().rowCount()).append(" 行\n");
            var cols = step.table().columns();
            int rows = Math.min(5, step.table().rows().size());
            for (int i = 0; i < rows; i++) {
                sb.append("- ");
                for (int c = 0; c < cols.size(); c++) {
                    if (c > 0) sb.append(", ");
                    sb.append(cols.get(c).getDisplay()).append("=").append(step.table().rows().get(i)[c]);
                }
                sb.append("\n");
            }
        }
        if (!missing.isEmpty()) {
            sb.append("\n缺失数据：").append(String.join("、", missing))
              .append("。必须在结论中明确告知。\n");
        }
        sb.append("\n请先给异常原因的最可能排序，再逐条列数据依据；只基于上方数据，禁止编造。");
        return sb.toString();
    }

    private void emitTemplateConclusion(CapabilityDefinition def, TableResult table,
                                        String timeRangeDisplay, StreamingModelClient.TokenSink sink) {
        StringBuilder sb = new StringBuilder();
        sb.append("已为你查询「").append(def.getDisplay()).append("」");
        if (timeRangeDisplay != null) sb.append("，统计区间 ").append(timeRangeDisplay);
        sb.append("。\n\n");
        if (table.rowCount() == 0) {
            sb.append("该条件下没有查询到数据，建议调整时间范围或检查车辆/车队选择。");
        } else {
            sb.append("共 ").append(table.rowCount()).append(" 条记录");
            if (table.truncated()) sb.append("（结果较多已截断，可缩小范围或导出明细）");
            sb.append("，明细见右侧表格与图表。\n\n关键数据：\n");
            // 取前 3 行前 3 列做摘要
            int rows = Math.min(3, table.rows().size());
            int cols = Math.min(3, table.columns().size());
            for (int i = 0; i < rows; i++) {
                StringBuilder line = new StringBuilder("- ");
                for (int c = 0; c < cols; c++) {
                    if (c > 0) line.append("：");
                    line.append(table.columns().get(c).getDisplay()).append(" ")
                            .append(table.rows().get(i)[c]);
                    if (table.columns().get(c).getUnit() != null) {
                        line.append(table.columns().get(c).getUnit());
                    }
                    if (c < cols - 1) line.append("，");
                }
                sb.append(line).append("\n");
            }
        }
        // 模拟流式输出（按小块回吐，保持前端流式体验）
        String text = sb.toString();
        int chunk = 12;
        for (int i = 0; i < text.length(); i += chunk) {
            sink.onToken(text.substring(i, Math.min(i + chunk, text.length())));
        }
    }

    /** 结论用户 prompt：问题 + 表格数据（全量行数受 max_rows 约束，≤1000 行） */
    private String buildConcludeUserPrompt(String question, CapabilityDefinition def,
                                           TableResult table, String timeRangeDisplay) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户问题：").append(question).append("\n");
        sb.append("查询能力：").append(def.getDisplay()).append("\n");
        if (timeRangeDisplay != null) sb.append("统计区间：").append(timeRangeDisplay).append("\n");
        sb.append("数据表（").append(table.rowCount()).append(" 行");
        if (table.truncated()) sb.append("，已截断");
        sb.append("）：\n");
        List<String> headers = table.columns().stream().map(CapabilityDefinition.ColumnDef::getDisplay).toList();
        sb.append(String.join(" | ", headers)).append("\n");
        int limit = Math.min(table.rows().size(), 50);
        for (int i = 0; i < limit; i++) {
            Object[] row = table.rows().get(i);
            StringBuilder line = new StringBuilder();
            for (int c = 0; c < row.length; c++) {
                if (c > 0) line.append(" | ");
                line.append(row[c]);
            }
            sb.append(line).append("\n");
        }
        if (table.rows().size() > limit) sb.append("...（其余 ").append(table.rows().size() - limit).append(" 行略）\n");
        return sb.toString();
    }

    /** 解析模型抽取输出（宽容解析 JSON） */
    @SuppressWarnings("unchecked")
    private CapabilitySelection parseSelection(String json) throws Exception {
        // 提取第一个 JSON 对象（模型可能包裹 markdown 代码块）
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end <= start) throw ApiException.schemaInvalid("模型输出不是 JSON");
        Map<String, Object> map = mapper.readValue(json.substring(start, end + 1), Map.class);
        Object id = map.get("capability_id");
        Object conf = map.get("confidence");
        Object params = map.get("params");
        return new CapabilitySelection(
                id == null ? null : String.valueOf(id),
                params instanceof Map ? (Map<String, Object>) params : Map.of(),
                conf instanceof Number n ? n.doubleValue() : 0,
                map.get("missing") instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of(),
                map.get("alternatives") instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of());
    }
}
