package com.dst.v2xagent.memory.model;

import java.util.Map;

/**
 * 记忆写入草稿（只能提议，不能直插）
 * 必附：来源 traceId、最窄范围、失效时间（默认 180 天）。
 */
public record MemoryDraft(
        String scopeType,
        String scopeKey,
        String content,
        String sourceTraceId,
        Map<String, Object> evidence,
        Integer expireDays
) {

    public int effectiveExpireDays() {
        return expireDays == null ? 180 : Math.min(365, Math.max(1, expireDays));
    }
}
