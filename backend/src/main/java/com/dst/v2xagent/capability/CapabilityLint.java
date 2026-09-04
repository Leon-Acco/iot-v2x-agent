package com.dst.v2xagent.capability;

import com.dst.v2xagent.capability.model.CapabilityDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 能力元数据质量检查（lint）
 * 给管理后台完整度评分：元数据越完整，Agent 召回越准，
 * 客户也能看懂工具用途与预期数据。
 * 每条问题扣 15 分，100 为满分。
 */
@Component
public class CapabilityLint {

    /** 单个能力检查结果 */
    public record LintResult(String id, int score, List<String> issues) {}

    /** 检查单个能力定义，返回评分与问题清单 */
    public LintResult lint(CapabilityDefinition def) {
        List<String> issues = new ArrayList<>();
        boolean orch = "orchestration".equals(def.getKind());

        if (def.getDescription() == null || def.getDescription().length() < 15) {
            issues.add("描述过短（<15字），模型难以理解用途");
        }
        if (def.getAliases() == null || def.getAliases().size() < 2) {
            issues.add("别名少于 2 个，召回覆盖不足");
        }
        if (def.getSampleQuestions() == null || def.getSampleQuestions().isEmpty()) {
            issues.add("缺少示例问题（召回语料/前端空态）");
        }
        if (def.getReturns() == null || def.getReturns().getColumns() == null
                || def.getReturns().getColumns().isEmpty()) {
            issues.add("返回列未定义，客户看不到预期数据");
        } else {
            long noDisplay = def.getReturns().getColumns().stream()
                    .filter(c -> c.getDisplay() == null || c.getDisplay().isBlank()).count();
            if (noDisplay > 0) {
                issues.add(noDisplay + " 个返回列缺少中文显示名");
            }
            long metricNoUnit = def.getReturns().getColumns().stream()
                    .filter(c -> "metric".equals(c.getSemantic())
                            && (c.getUnit() == null || c.getUnit().isBlank())).count();
            if (metricNoUnit > 0) {
                issues.add(metricNoUnit + " 个指标列缺少单位");
            }
        }
        if (def.getChartHint() == null || def.getChartHint().isBlank()) {
            issues.add("缺少图表建议（chartHint）");
        }
        if (!orch) {
            if (def.getSourceTables() == null || def.getSourceTables().isEmpty()) {
                issues.add("未声明来源表（时效水位无从计算）");
            }
            if (def.getFreshnessPolicy() == null) {
                issues.add("未声明时效策略");
            }
            if (def.getParams() != null) {
                long noParamDesc = def.getParams().stream()
                        .filter(p -> p.getDescription() == null || p.getDescription().isBlank()).count();
                if (noParamDesc > 0) {
                    issues.add(noParamDesc + " 个参数缺少说明");
                }
            }
        }
        int score = Math.max(0, 100 - issues.size() * 15);
        return new LintResult(def.getId(), score, issues);
    }

    /** 批量检查 */
    public List<LintResult> lintAll(List<CapabilityDefinition> defs) {
        return defs.stream().map(this::lint).toList();
    }
}
