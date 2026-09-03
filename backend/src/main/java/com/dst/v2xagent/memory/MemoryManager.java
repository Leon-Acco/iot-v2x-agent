package com.dst.v2xagent.memory;

import com.dst.v2xagent.memory.model.Memory;
import com.dst.v2xagent.memory.model.MemoryQuery;
import com.dst.v2xagent.memory.model.WorkingMemory;

import java.util.List;

/**
 * 记忆管理器：Agent 访问记忆的唯一入口
 * 禁止 Agent 直接接触 redisTemplate / jdbcTemplate / vectorStore。
 * 原则：Memory 是线索不是事实；最新事实必须来自 Capability。
 */
public interface MemoryManager {

    /** 读取当前会话工作记忆（不存在返回空上下文） */
    WorkingMemory getWorkingMemory(String tenantId, Long userId, String sessionId);

    /** 保存工作记忆（会话上下文） */
    void saveWorkingMemory(WorkingMemory workingMemory);

    /** 召回：EXACT / RECENT / SEMANTIC / IMPORTANT 合并 → Rerank → TopK */
    List<Memory> recall(MemoryQuery query);

    /** 写入记忆，返回 id（EPISODE / SEMANTIC / VEHICLE / FLEET） */
    Long remember(Memory memory);

    /** 更新记忆（仅语义层支持） */
    void update(Memory memory);

    /** 遗忘（按层 + id 删除） */
    void forget(String layer, Long id);
}
