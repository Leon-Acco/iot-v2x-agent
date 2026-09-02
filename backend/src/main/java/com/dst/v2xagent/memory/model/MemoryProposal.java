package com.dst.v2xagent.memory.model;

import java.time.LocalDateTime;

/** 记忆写入提议（待审核） */
public record MemoryProposal(
        long id,
        String scopeType,
        String scopeKey,
        String content,
        String proposedBy,
        String status,
        LocalDateTime createdAt
) {}
