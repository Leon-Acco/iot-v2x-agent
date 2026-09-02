package com.dst.v2xagent.memory.model;

import java.util.List;

/** 召回结果：候选已回 MySQL 二次验权（ACTIVE + 未过期 + ACL 命中） */
public record RecallResult(List<MemoryItem> items) {

    public boolean isEmpty() {
        return items == null || items.isEmpty();
    }
}
