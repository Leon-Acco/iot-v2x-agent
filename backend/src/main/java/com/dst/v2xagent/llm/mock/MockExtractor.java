package com.dst.v2xagent.llm.mock;

import com.dst.v2xagent.capability.model.CapabilityDefinition;
import com.dst.v2xagent.runtime.model.CapabilitySelection;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mock 规则抽取器（无 LLM Key 时的开发联调与降级路径）
 * 用别名/关键词规则完成意图识别与槽位抽取，覆盖 P0 高频问法；
 * 真实模型故障时也可降级为「纯规则 + 澄清」。
 */
@Component
public class MockExtractor {

    /** 超范围问题硬拒：避免“修改绑定手机号”误中离线别名的二元组 */
    private static final Pattern OUT_OF_SCOPE = Pattern.compile(
            "(天气|机票|酒店|好吃|手机号|绑定|修改|写一首诗|播放|讲个笑话|快递|外卖|股票|发票)");
    private static final Pattern TIME_EXPR = Pattern.compile(
            "(近\\s*\\d+\\s*天|过去\\s*\\d+\\s*天|近\\s*\\d+\\s*个?小时|今天|今日|昨天|昨日|前天|本周|这周|上周|本月|这个月|上月)");
    private static final Pattern PLATE = Pattern.compile("[\\u4e00-\\u9fa5][A-Z][A-Z0-9]{5,6}");
    private static final Pattern VIN = Pattern.compile("[A-Z0-9]{17}");
    private static final Pattern OFFLINE_HOURS = Pattern.compile("(?:离线|掉线|失联)\\s*超?过?\\s*(\\d+)\\s*个?小时");

    /**
     * 规则抽取：对候选打分 → 取最高分 → 按参数定义填槽
     */
    public CapabilitySelection extract(String question, List<CapabilityDefinition> candidates) {
        if (OUT_OF_SCOPE.matcher(question).find()) {
            return new CapabilitySelection(null, Map.of(), 0, List.of(), List.of());
        }
        CapabilityDefinition best = null;
        double bestScore = 0;
        List<String> alternatives = new ArrayList<>();
        for (CapabilityDefinition def : candidates) {
            double s = score(question, def);
            if (s > bestScore) {
                if (best != null && bestScore > 0) alternatives.add(best.getId());
                best = def;
                bestScore = s;
            } else if (s > 0) {
                alternatives.add(def.getId());
            }
        }
        if (best == null || bestScore < 1.2) {
            // 低于阈值视为未覆盖：防止「今天天气怎么样」因零星 bigram 重合误命中
            return new CapabilitySelection(null, Map.of(), 0, List.of(), List.of());
        }

        Map<String, Object> params = new HashMap<>();
        // 时间槽位
        Matcher tm = TIME_EXPR.matcher(question);
        String timeExpr = tm.find() ? tm.group(1).replaceAll("\\s+", "") : null;
        // 车辆槽位
        String vehicle = extractVehicle(question);
        // 离线时长槽位
        Matcher om = OFFLINE_HOURS.matcher(question);

        for (CapabilityDefinition.ParamDef p : best.getParams()) {
            switch (p.getName()) {
                case "time_range" -> {
                    if (timeExpr != null) params.put("time_range", timeExpr);
                }
                case "vehicle" -> {
                    if (vehicle != null) params.put("vehicle", vehicle);
                }
                case "offline_hours" -> {
                    if (om.find()) params.put("offline_hours", Integer.parseInt(om.group(1)));
                }
                case "alarm_type" -> {
                    if (p.getEnumValues() != null) {
                        p.getEnumValues().stream().filter(question::contains).findFirst()
                                .ifPresent(v -> params.put("alarm_type", v));
                    }
                }
                default -> { /* fleet_id 等不抽取 */ }
            }
        }

        // 必填参数缺失列表（上层转澄清）
        List<String> missing = new ArrayList<>();
        for (CapabilityDefinition.ParamDef p : best.getParams()) {
            if (p.isRequired() && !params.containsKey(p.getName()) && p.getDefaultValue() == null) {
                missing.add(p.getName());
            }
        }

        double confidence = bestScore >= 3 ? 0.9 : (bestScore >= 1.5 ? 0.7 : 0.5);
        return new CapabilitySelection(best.getId(), params, confidence, missing,
                alternatives.stream().limit(2).toList());
    }

    /** 打分：display 全命中 +3，别名全命中 +2，别名/显示名二元组（bigram）重合每个 +0.4 */
    private double score(String question, CapabilityDefinition def) {
        double s = 0;
        if (question.contains(def.getDisplay())) s += 3;
        for (String alias : def.getAliases()) {
            if (!alias.isBlank() && question.contains(alias)) s += 2;
        }
        // 二元组重合：覆盖「各车队近7天里程对比」这类插入了时间词的问法
        s += 0.4 * bigramHits(question, def.getDisplay());
        for (String alias : def.getAliases()) {
            s += 0.4 * bigramHits(question, alias);
        }
        for (String sample : def.getSampleQuestions()) {
            s += 0.2 * bigramHits(question, sample);
        }
        return s;
    }

    /** 统计 keyword 的二元组在问题中的命中数 */
    private int bigramHits(String question, String keyword) {
        if (keyword == null || keyword.length() < 2) return 0;
        String k = keyword.replaceAll("\\s+", "");
        int hits = 0;
        for (int i = 0; i < k.length() - 1; i++) {
            if (question.contains(k.substring(i, i + 2))) hits++;
        }
        return hits;
    }

    private String extractVehicle(String question) {
        Matcher vm = VIN.matcher(question);
        if (vm.find()) return vm.group();
        Matcher pm = PLATE.matcher(question);
        if (pm.find()) return pm.group();
        return null;
    }
}
