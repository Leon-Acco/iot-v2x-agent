package com.dst.v2xagent.memory.model;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 统一记忆条目（MemoryManager 的唯一载体）
 * layer：WORKING / EPISODE / SEMANTIC / VEHICLE / FLEET
 * 原则：Memory 是线索不是事实，最新业务事实必须来自 Capability。
 */
public record Memory(
        Long id,
        String layer,
        String tenantId,
        /** 主体类型：VEHICLE / FLEET / USER / SESSION / GLOBAL */
        String subjectType,
        String subjectId,
        /** 类型：PATTERN / PREFERENCE / FACT / ANALYSIS / TASK */
        String memoryType,
        String content,
        /** 重要度 0~1（沉淀价值） */
        double importance,
        /** 置信度 0~1 */
        double confidence,
        /** 来源：生成它的任务 / 管线 */
        String source,
        /** 结果引用（result_id 列表，不存真实数据） */
        java.util.List<String> resultRefs,
        /** 实体索引（fleet / vins 等） */
        Map<String, Object> entities,
        LocalDateTime createdAt
) {
    /** 新建记忆（无 id） */
    public static Memory of(String layer, String tenantId, String subjectType, String subjectId,
                            String memoryType, String content, double importance, double confidence, String source) {
        return new Memory(null, layer, tenantId, subjectType, subjectId, memoryType, content,
                importance, confidence, source, java.util.List.of(), java.util.Map.of(), null);
    }
}
