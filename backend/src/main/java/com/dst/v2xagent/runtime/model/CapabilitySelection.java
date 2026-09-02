package com.dst.v2xagent.runtime.model;

import java.util.List;
import java.util.Map;

/**
 * LLM 意图/槽位抽取输出（结构化）
 * 注意：Schema 中不存在 sql 字段——模型只能产出 capability_id + 参数，永不产出 SQL。
 */
public record CapabilitySelection(
        String capabilityId,
        Map<String, Object> params,
        double confidence,
        List<String> missing,
        List<String> alternatives
) {}
