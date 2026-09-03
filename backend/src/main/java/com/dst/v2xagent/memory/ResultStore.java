package com.dst.v2xagent.memory;

import com.dst.v2xagent.capability.TableResultJsonCodec;
import com.dst.v2xagent.capability.model.TableResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 结果存储（Redis）：查询结果的唯一落点
 * Memory 只保存 result_id 引用，真实数据一律经由本类存取。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResultStore {

    private final StringRedisTemplate redisTemplate;

    private static final Duration DEFAULT_TTL = Duration.ofHours(2);

    private String key(String resultId) {
        return "result:" + resultId;
    }

    /** 保存结果，返回 result_id（r + 12 位） */
    public String save(TableResult table) {
        return save(table, DEFAULT_TTL);
    }

    /** 保存结果（指定 TTL） */
    public String save(TableResult table, Duration ttl) {
        String resultId = "r" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try {
            redisTemplate.opsForValue().set(key(resultId), TableResultJsonCodec.encode(table), ttl);
        } catch (Exception e) {
            log.warn("结果存储写入失败: {}", e.getMessage());
        }
        return resultId;
    }

    /** 读取结果 */
    public Optional<TableResult> load(String resultId) {
        try {
            String json = redisTemplate.opsForValue().get(key(resultId));
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(TableResultJsonCodec.decode(json));
        } catch (Exception e) {
            log.warn("结果存储读取失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** 结果摘要（进 Memory / 前端展示，不带数据） */
    public Map<String, Object> summary(String resultId, String type) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("result_id", resultId);
        out.put("type", type);
        Long ttl = redisTemplate.getExpire(key(resultId));
        out.put("ttl_seconds", ttl);
        return out;
    }
}
