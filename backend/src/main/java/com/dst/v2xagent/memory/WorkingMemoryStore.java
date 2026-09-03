package com.dst.v2xagent.memory;

import com.dst.v2xagent.memory.model.WorkingMemory;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * 工作记忆存储（Redis，TTL 24h）
 * 只存上下文状态与结果引用，不存完整查询结果（那是 ResultStore 的职责）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkingMemoryStore {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final Duration TTL = Duration.ofHours(24);

    private String key(String tenantId, Long userId, String sessionId) {
        return "wm:" + tenantId + ":" + userId + ":" + sessionId;
    }

    /** 保存工作记忆（失败只告警，不影响主链路） */
    public void save(WorkingMemory wm) {
        try {
            redisTemplate.opsForValue().set(key(wm.tenantId(), wm.userId(), wm.sessionId()),
                    mapper.writeValueAsString(Map.of("currentContext", wm.currentContext())), TTL);
        } catch (Exception e) {
            log.warn("工作记忆写入失败: {}", e.getMessage());
        }
    }

    /** 读取工作记忆 */
    @SuppressWarnings("unchecked")
    public Optional<WorkingMemory> load(String tenantId, Long userId, String sessionId) {
        try {
            String json = redisTemplate.opsForValue().get(key(tenantId, userId, sessionId));
            if (json == null) {
                return Optional.empty();
            }
            Map<String, Object> map = mapper.readValue(json, Map.class);
            Map<String, Object> ctx = (Map<String, Object>) map.get("currentContext");
            return Optional.of(new WorkingMemory(tenantId, userId, sessionId,
                    ctx == null ? Map.of() : ctx));
        } catch (Exception e) {
            log.warn("工作记忆读取失败: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
