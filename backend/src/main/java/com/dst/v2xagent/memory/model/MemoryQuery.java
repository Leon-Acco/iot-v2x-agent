package com.dst.v2xagent.memory.model;

import java.util.List;

/**
 * 记忆召回查询（MemoryManager.recall 的输入）
 * modes：EXACT（主体精确）/ RECENT（最近）/ SEMANTIC（全文）/ IMPORTANT（重要度）
 */
public record MemoryQuery(
        String tenantId,
        Long userId,
        String sessionId,
        String subjectType,
        String subjectId,
        String text,
        List<String> layers,
        List<String> modes,
        Integer topK
) {
    public int effectiveTopK() {
        return topK == null ? 5 : Math.min(20, Math.max(1, topK));
    }

    public List<String> effectiveLayers() {
        return layers == null || layers.isEmpty()
                ? List.of("EPISODE", "SEMANTIC", "VEHICLE", "FLEET") : layers;
    }

    public List<String> effectiveModes() {
        return modes == null || modes.isEmpty() ? List.of("EXACT", "RECENT") : modes;
    }
}
