package com.dst.v2xagent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 会话短记忆（Redis，最近 10 轮，TTL 24h）
 * 保存上一个「查询意图」QueryIntent，支撑多轮变形（「那前天呢」「还是刚才那台车」）。
 * 丢失后可由 MySQL 消息重建，Redis 不是事实来源。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShortTermMemory {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final Duration TTL = Duration.ofHours(24);

    /** 上一轮的查询意图（可继承的多轮上下文） */
    public record QueryIntent(
            String capabilityId,
            Map<String, Object> params,
            List<String> resolvedVins,
            String at
    ) {}

    private String intentKey(String sessionId) { return "sess:" + sessionId + ":intent"; }

    /** 保存本轮意图 */
    public void saveIntent(String sessionId, QueryIntent intent) {
        try {
            redisTemplate.opsForValue().set(intentKey(sessionId), mapper.writeValueAsString(intent), TTL);
        } catch (Exception e) {
            log.warn("短记忆写入失败（不影响主链路）: {}", e.getMessage());
        }
    }

    /** 读取上一轮意图 */
    @SuppressWarnings("unchecked")
    public Optional<QueryIntent> loadIntent(String sessionId) {
        try {
            String json = redisTemplate.opsForValue().get(intentKey(sessionId));
            if (json == null) return Optional.empty();
            Map<String, Object> map = mapper.readValue(json, Map.class);
            return Optional.of(new QueryIntent(
                    (String) map.get("capabilityId"),
                    (Map<String, Object>) map.get("params"),
                    (List<String>) map.get("resolvedVins"),
                    (String) map.get("at")));
        } catch (Exception e) {
            log.warn("短记忆读取失败（按新会话处理）: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
