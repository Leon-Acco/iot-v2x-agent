package com.dst.v2xagent.visualization;

import com.dst.v2xagent.agui.auth.AuthFilter;
import com.dst.v2xagent.capability.model.TableResult;
import com.dst.v2xagent.common.ApiException;
import com.dst.v2xagent.common.PermissionContext;
import com.dst.v2xagent.memory.ResultStore;
import com.dst.v2xagent.semantic.SemanticQueryService;
import com.dst.v2xagent.semantic.model.SemanticQuery;
import com.dst.v2xagent.semantic.model.SemanticResult;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 可视化规划端点：Semantic Query → ChartSpec；切换图表不重调 Agent
 */
@RestController
@RequestMapping("/ag-ui/viz")
@RequiredArgsConstructor
public class VisualizationController {

    private final SemanticQueryService semanticQueryService;
    private final VisualizationPlanner visualizationPlanner;
    private final ResultStore resultStore;

    /** 语义查询 + 出图：结果入 ResultStore，ChartSpec 携带 data.source=result_id */
    @PostMapping("/plan")
    public ChartSpec plan(@RequestBody SemanticQuery query, HttpServletRequest request) {
        PermissionContext ctx = requireCtx(request);
        SemanticResult result = semanticQueryService.execute(query, ctx);
        // 语义结果转表结果入存（统一引用体系）
        String resultId = resultStore.save(toTable(result));
        return visualizationPlanner.plan(result, resultId);
    }

    /** 切换图表类型：只重出契约，不重查数据、不调模型 */
    @PostMapping("/replan")
    public ChartSpec replan(@RequestBody Map<String, String> body, HttpServletRequest request) {
        requireCtx(request);
        String resultId = body.get("result_id");
        String type = body.get("type");
        if (resultId == null || resultId.isBlank()) {
            throw ApiException.paramInvalid("缺少 result_id");
        }
        TableResult table = resultStore.load(resultId)
                .orElseThrow(() -> ApiException.paramInvalid("结果已过期或不存在: " + resultId));
        String title = body.getOrDefault("title", "");
        return visualizationPlanner.replan(table, title, resultId, type);
    }

    private PermissionContext requireCtx(HttpServletRequest request) {
        PermissionContext ctx = (PermissionContext) request.getAttribute(AuthFilter.ATTR_PERMISSION);
        if (ctx == null) {
            throw ApiException.unauthorized("未认证");
        }
        return ctx;
    }

    /** 语义结果 → 表结果（统一存储载体） */
    private TableResult toTable(SemanticResult result) {
        java.util.List<com.dst.v2xagent.capability.model.CapabilityDefinition.ColumnDef> columns = new java.util.ArrayList<>();
        for (String dim : result.dimensions()) {
            com.dst.v2xagent.capability.model.CapabilityDefinition.ColumnDef col =
                    new com.dst.v2xagent.capability.model.CapabilityDefinition.ColumnDef();
            col.setName(dim);
            col.setSemantic("day".equals(dim) || "hour".equals(dim) ? "time" : "category");
            columns.add(col);
        }
        com.dst.v2xagent.capability.model.CapabilityDefinition.ColumnDef value =
                new com.dst.v2xagent.capability.model.CapabilityDefinition.ColumnDef();
        value.setName("value");
        value.setSemantic("metric");
        value.setUnit(result.unit());
        columns.add(value);
        java.util.List<Object[]> rows = new java.util.ArrayList<>();
        for (Map<String, Object> row : result.rows()) {
            Object[] r = new Object[columns.size()];
            int i = 0;
            for (String dim : result.dimensions()) {
                r[i++] = row.get(dim);
            }
            r[i] = row.get("value");
            rows.add(r);
        }
        return new TableResult(columns, rows, rows.size(), false, null, null);
    }
}
