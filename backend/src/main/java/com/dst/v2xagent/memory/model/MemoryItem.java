package com.dst.v2xagent.memory.model;

import java.time.LocalDateTime;

/** 组织长记忆条目（MySQL 为唯一事实库） */
public record MemoryItem(
        long id,
        String scopeType,
        String scopeKey,
        String content,
        String sourceTraceId,
        long sourceVersion,
        LocalDateTime expireAt,
        LocalDateTime updatedAt
) {}
