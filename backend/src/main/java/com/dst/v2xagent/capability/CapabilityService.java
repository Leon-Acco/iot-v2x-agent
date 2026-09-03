package com.dst.v2xagent.capability;

import com.dst.v2xagent.capability.model.CapabilityDefinition;
import com.dst.v2xagent.capability.model.TableResult;
import com.dst.v2xagent.common.PermissionContext;
import com.dst.v2xagent.orchestration.OrchestrationExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.util.Map;

/**
 * capability 调用统一入口：AG-UI 主链路与 tool-result 重跑共用
 * 入口完成：注册中心取定义 → 八步参数解析 → 执行 → 出图
 */
@Service
@RequiredArgsConstructor
public class CapabilityService {

    private final CapabilityRegistry registry;
    private final ParamResolver paramResolver;
    private final CapabilityFirewall firewall;
    private final CapabilityExecutor executor;
    private final RenderChartTool renderChartTool;
    private final OrchestrationExecutor orchestrationExecutor;

    /** 调用产物：表格结果 + 图表 spec + 解析信息（回显用） */
    public record InvokeOutcome(
            CapabilityDefinition definition,
            ParamResolver.ResolvedParams resolved,
            TableResult table,
            RenderChartTool.ChartSpec chart
    ) {}

    /**
     * 执行一次 capability 调用。
     * @param capabilityId 能力 id
     * @param rawParams    业务参数（不含权限参数，权限由管线注入）
     * @param ctx          权限上下文
     */
    public InvokeOutcome invoke(String capabilityId, Map<String, Object> rawParams, PermissionContext ctx) {
        CapabilityDefinition def = registry.require(capabilityId);
        ParamResolver.ResolvedParams resolved = paramResolver.resolve(def, rawParams, ctx, ZonedDateTime.now());
        // 防火墙统一管线：状态 → scope → 参数策略 → 限流（不过即拒）
        firewall.check(def, resolved, ctx);
        // 编排引用型：走模板执行，主表/出图按模板声明的步骤产出
        if ("orchestration".equals(def.getKind())) {
            java.util.Map<String, Object> orchInput = new java.util.HashMap<>(rawParams);
            orchInput.putIfAbsent("time_range", "近7天");
            OrchestrationExecutor.OrchestrationOutcome outcome =
                    orchestrationExecutor.execute(def.getId(), orchInput, ctx);
            OrchestrationExecutor.StepOutcome primary =
                    outcome.steps().get(outcome.template().getOutput().getPrimaryTable());
            if (primary == null || !primary.ok()) {
                throw com.dst.v2xagent.common.ApiException.dataUnavailable("编排主步骤未产出数据");
            }
            RenderChartTool.ChartSpec chart = null;
            OrchestrationExecutor.StepOutcome chartStep =
                    outcome.steps().get(outcome.template().getOutput().getChart());
            if (chartStep != null && chartStep.ok()) {
                chart = renderChartTool.render(registry.require(chartStep.capabilityId()), chartStep.table());
            }
            return new InvokeOutcome(def, resolved, primary.table(), chart);
        }
        TableResult table = executor.execute(def, resolved, ctx);
        RenderChartTool.ChartSpec chart = renderChartTool.render(def, table);
        return new InvokeOutcome(def, resolved, table, chart);
    }

    /** 管理后台试跑（dry-run）：同样走完整管线与护栏，且走审计（由调用方记） */
    public InvokeOutcome dryRun(String capabilityId, Map<String, Object> rawParams, PermissionContext ctx) {
        CapabilityDefinition def = registry.require(capabilityId);
        ParamResolver.ResolvedParams resolved = paramResolver.resolve(def, rawParams, ctx, ZonedDateTime.now());
        // dry-run 同样过防火墙（管理员试跑也不越权）
        firewall.check(def, resolved, ctx);
        TableResult table = executor.execute(def, resolved, ctx);
        RenderChartTool.ChartSpec chart = renderChartTool.render(def, table);
        return new InvokeOutcome(def, resolved, table, chart);
    }
}
