package com.dst.v2xagent.capability;

import com.dst.v2xagent.capability.model.CapabilityDefinition;
import com.dst.v2xagent.common.PermissionContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * capability 选择器：scope 预过滤 → 关键词/别名召回 → 上下文加成 → Top-8
 * scope 预过滤必须在最前面：先召回再滤权限会让模型在拒答文案里泄露能力存在性。
 */
@Component
@RequiredArgsConstructor
public class CapabilitySelector {

    private final CapabilityRegistry registry;

    private static final int TOP_K = 8;

    /**
     * 为用户问题召回候选 capability（Top-8 进 prompt）
     * @param question     用户问题
     * @param ctx          权限上下文
     * @param lastCapabilityId 上一轮命中的 capability（上下文加成）
     */
    public List<CapabilityDefinition> select(String question, PermissionContext ctx, String lastCapabilityId) {
        // 1. scope 预过滤：无权限的能力直接不进候选
        List<CapabilityDefinition> candidates = registry.listOnline().stream()
                .filter(def -> ctx.scopes().containsAll(def.getScopes()))
                .toList();

        String q = question == null ? "" : question;
        List<ScoredCapability> scored = new ArrayList<>();
        for (CapabilityDefinition def : candidates) {
            double score = score(q, def);
            // 3. 上下文加成：上一轮 capability 提权（多轮变形「那前天呢」）
            if (def.getId().equals(lastCapabilityId)) {
                score += 0.5;
            }
            scored.add(new ScoredCapability(def, score));
        }
        // 2. 按得分排序取 Top-8（0 分也保留：抽取模型结合 description 兜底判断）
        return scored.stream()
                .sorted(Comparator.comparingDouble(ScoredCapability::score).reversed())
                .limit(TOP_K)
                .map(ScoredCapability::def)
                .toList();
    }

    /** 关键词/别名打分：命中 display +2，别名 +1.5，示例问题 +1，域词 +0.5 */
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
