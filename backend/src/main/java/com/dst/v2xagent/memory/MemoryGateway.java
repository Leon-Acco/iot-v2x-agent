package com.dst.v2xagent.memory;

import com.dst.v2xagent.common.PermissionContext;
import com.dst.v2xagent.memory.model.MemoryDraft;
import com.dst.v2xagent.memory.model.MemoryItem;
import com.dst.v2xagent.memory.model.MemoryProposal;
import com.dst.v2xagent.memory.model.RecallQuery;
import com.dst.v2xagent.memory.model.RecallResult;

import java.util.List;

/**
 * 记忆网关（设计文档 §8.4）：PermissionContext 是第一个参数且不可为 null。
 * 四类权限：memory.read.explicit / memory.read.org / memory.write.proposal / memory.delete.own。
 */
public interface MemoryGateway {

    /** 模糊召回：需 memory.read.org；ACL 预过滤 + MySQL 二次验权（fail closed） */
    RecallResult recall(PermissionContext ctx, RecallQuery query);

    /** 显式引用唯一入口（A2A 只能读已授权引用，逐条校验） */
    List<MemoryItem> loadExplicit(PermissionContext ctx, List<String> memoryRefs);

    /** 写入只能提议：需 memory.write.proposal，进待审队列 */
    MemoryProposal propose(PermissionContext ctx, MemoryDraft draft);

    /** 删除自己提交/归属的记忆（仅人类用户，需 memory.delete.own） */
    void deleteOwn(PermissionContext ctx, String memoryId);
}
