package com.dst.v2xagent.capability;

import com.dst.v2xagent.capability.model.CapabilityDefinition;
import com.dst.v2xagent.common.PermissionContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * capability 选择器：scope 预过滤 → profile 域裁剪 → 关键词/别名召回 → 上下文加成 → 全量（规模超限才截断）
 * scope 预过滤必须在最前面：先召回再滤权限会让模型在拒答文案里泄露能力存在性。
 * 候选以 function calling 工具形式全量下发（模型语义选择为主）；打分仅作为规模超限时的截断排序依据。
 */
@Component
@RequiredArgsConstructor
public class CapabilitySelector {

    private final CapabilityRegistry registry;

    /** 下发给模型的最大工具数（能力规模超限时的截断上限，含兜底工具） */
    private static final int MAX_TOOLS = 32;

    /**
     * 为用户问题召回候选 capability（function calling 工具候选池）
     * @param question     用户问题
     * @param ctx          权限上下文
     * @param domainFilter profile 允许的域（空 = 不裁剪）；域裁剪在召回前，避免域外高分能力挤占名额
     * @param lastCapabilityId 上一轮命中的 capability（上下文加成）
     */
    public List<CapabilityDefinition> select(String question, PermissionContext ctx,
                                             Collection<String> domainFilter, String lastCapabilityId) {
        // 1. scope 预过滤 + profile 域裁剪：无权限/域外的能力直接不进候选
        List<CapabilityDefinition> candidates = registry.listOnline().stream()
                .filter(def -> ctx.scopes().containsAll(def.getScopes()))
                .filter(def -> domainFilter == null || domainFilter.isEmpty()
                        || domainFilter.contains(def.getDomain()))
                .toList();

        // 2. 规模未超限：全量下发（语义选择交给模型）
        if (candidates.size() <= MAX_TOOLS) {
            return candidates;
        }

        // 3. 规模超限：关键词/别名打分排序 + 上下文加成，截断到 MAX_TOOLS
        String q = question == null ? "" : question;
        List<ScoredCapability> scored = new ArrayList<>();
        for (CapabilityDefinition def : candidates) {
            double score = score(q, def);
            // 上下文加成：上一轮 capability 提权（多轮变形「那前天呢」）
            if (def.getId().equals(lastCapabilityId)) {
                score += 0.5;
            }
            scored.add(new ScoredCapability(def, score));
        }
        return scored.stream()
                .sorted(Comparator.comparingDouble(ScoredCapability::score).reversed())
                .limit(MAX_TOOLS)
                .map(ScoredCapability::def)
                .toList();
    }

    /** 关键词/别名打分（仅规模超限截断时使用）：命中 display +2，别名 +1.5，示例问题分词 +0.3 */
    private double score(String question, CapabilityDefinition def) {
        double s = 0;
        if (contains(question, def.getDisplay())) s += 2;
        for (String alias : def.getAliases()) {
            if (contains(question, alias)) s += 1.5;
        }
        for (String sample : def.getSampleQuestions()) {
            for (String token : sample.replaceAll("[？?，,。.\\s]+", " ").split(" ")) {
                if (token.length() >= 2 && question.contains(token)) {
                    s += 0.3;
                    break;
                }
            }
        }
        return s;
    }

    private boolean contains(String text, String keyword) {
        return keyword != null && !keyword.isBlank() && text.contains(keyword);
    }

    private record ScoredCapability(CapabilityDefinition def, double score) {}
}
