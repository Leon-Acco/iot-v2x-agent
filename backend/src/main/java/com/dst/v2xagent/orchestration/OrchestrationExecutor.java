package com.dst.v2xagent.orchestration;

import com.dst.v2xagent.capability.CapabilityExecutor;
import com.dst.v2xagent.capability.CapabilityRegistry;
import com.dst.v2xagent.capability.ParamResolver;
import com.dst.v2xagent.capability.model.CapabilityDefinition;
import com.dst.v2xagent.capability.model.TableResult;
import com.dst.v2xagent.common.ApiException;
import com.dst.v2xagent.common.PermissionContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 编排执行器（设计文档 §5.9）：按 parallel_group 分批，批内虚拟线程并行；
 * required 步骤失败则整体失败，非 required 失败则降级并声明缺失。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrchestrationExecutor {

    private final OrchestrationRegistry orchestrationRegistry;
    private final CapabilityRegistry capabilityRegistry;
    private final ParamResolver paramResolver;
    private final CapabilityExecutor executor;
    private final ExecutorService groupExecutor = Executors.newVirtualThreadPerTaskExecutor();

    /** 单步执行结果：failure 非 null 表示失败 */
    public record StepOutcome(String stepId, String capabilityId, String display,
                              TableResult table, String failure) {
        public boolean ok() {
            return table != null;
        }
    }

    /** 编排执行结果：步骤结果 + 缺失声明（非 required 失败项） */
    public record OrchestrationOutcome(OrchestrationTemplate template,
                                       Map<String, StepOutcome> steps,
                                       List<String> missing) {}

    /**
     * 执行编排模板。
     * @param inputParams 输入参数（原始槽位值 + 默认值，含 vehicle/time_range）
     */
    public OrchestrationOutcome execute(String templateId, Map<String, Object> inputParams,
                                        PermissionContext ctx) {
        OrchestrationTemplate template = orchestrationRegistry.require(templateId);
        Map<String, StepOutcome> results = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();

        Map<Integer, List<OrchestrationTemplate.Step>> groups = template.getSteps().stream()
                .collect(Collectors.groupingBy(OrchestrationTemplate.Step::getParallelGroup,
                        TreeMap::new, Collectors.toList()));
        long deadline = System.currentTimeMillis() + template.getBudget().getTotalTimeoutMs();

        for (Map.Entry<Integer, List<OrchestrationTemplate.Step>> entry : groups.entrySet()) {
            List<OrchestrationTemplate.Step> batch = entry.getValue();
            List<CompletableFuture<StepOutcome>> futures = batch.stream()
                    .map(step -> CompletableFuture.supplyAsync(
                            () -> runStep(step, inputParams, ctx), groupExecutor))
                    .toList();
            for (int i = 0; i < futures.size(); i++) {
                OrchestrationTemplate.Step step = batch.get(i);
                StepOutcome outcome;
                try {
                    long remain = Math.max(1, deadline - System.currentTimeMillis());
                    outcome = futures.get(i).get(remain, TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    String display = capabilityRegistry.get(step.getCapability())
                            .map(CapabilityDefinition::getDisplay).orElse(step.getCapability());
                    outcome = new StepOutcome(step.getId(), step.getCapability(), display,
                            null, rootMessage(e));
                    log.warn("编排步骤失败 {}({}): {}", step.getId(), step.getCapability(), outcome.failure());
                }
                results.put(step.getId(), outcome);
                if (!outcome.ok()) {
                    if (step.isRequired()) {
                        throw new ApiException("ORCHESTRATION_STEP_FAILED", "invoke", true,
                                "关键取证步骤失败：" + outcome.display());
                    }
                    missing.add(outcome.display());
                }
            }
        }
        return new OrchestrationOutcome(template, results, missing);
    }

    /** 单步执行：参数替换 → 八步解析（含 ACL 注入）→ 执行器 */
    private StepOutcome runStep(OrchestrationTemplate.Step step, Map<String, Object> inputParams,
                                PermissionContext ctx) {
        CapabilityDefinition def = capabilityRegistry.require(step.getCapability());
        Map<String, Object> params = substitute(step.getParams(), inputParams);
        ParamResolver.ResolvedParams resolved = paramResolver.resolve(def, params, ctx, ZonedDateTime.now());
        TableResult table = executor.execute(def, resolved, ctx);
        return new StepOutcome(step.getId(), def.getId(), def.getDisplay(), table, null);
    }

    /** 参数引用替换：${input.xxx}；为 null 的引用不传递（走 capability 默认） */
    private Map<String, Object> substitute(Map<String, String> tplParams, Map<String, Object> inputParams) {
        Map<String, Object> out = new HashMap<>();
        tplParams.forEach((k, v) -> {
            if (v == null) {
                return;
            }
            if (v.startsWith("${input.") && v.endsWith("}")) {
                Object val = inputParams.get(v.substring(8, v.length() - 1));
                if (val != null) {
                    out.put(k, val);
                }
            } else if (!v.contains("${")) {
                out.put(k, v);
            }
        });
        return out;
    }

    private String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        String msg = String.valueOf(t.getMessage());
        return msg.length() > 120 ? msg.substring(0, 120) : msg;
    }
}
