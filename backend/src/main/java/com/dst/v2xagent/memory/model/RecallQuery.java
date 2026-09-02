package com.dst.v2xagent.memory.model;

import java.util.List;

/**
 * 召回查询：只接受结构化字段，不接受模型/用户传入原始查询语法。
 * @param text 关键词（服竡干词后拼 FULLTEXT BOOLEAN MODE）
 * @param scopeTypes 范围类型过滤（可空）
 * @param topK 返回上限（默认 3，上限 20）
 */
public record RecallQuery(String text, List<String> scopeTypes, Integer topK) {

    public int effectiveTopK() {
        return topK == null ? 3 : Math.min(20, Math.max(1, topK));
    }
}
