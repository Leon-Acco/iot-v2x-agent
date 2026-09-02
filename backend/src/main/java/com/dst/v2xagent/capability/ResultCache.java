package com.dst.v2xagent.capability;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.dst.v2xagent.capability.model.TableResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * capability 结果缓存：L1 Caffeine（30s 抖动吹平）+ L2 Redis（跨副本共享）
 * key 已含 tenant + 权限指纹 + capability 版本（上游组装），权限变更自动失效。
 * 大结果（>200KB）P0 暂不缓存。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResultCache {

    private final StringRedisTemplate redisTemplate;

    private static final String KEY_PREFIX = "cap:result:";
    private static final int MAX_CACHE_BYTES = 200 * 1024;

    /** L1 本地缓存：30 秒 */
    private final Cache<String, TableResult> l1 = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .build();

    public TableResult get(String key) {
        TableResult hit = l1.getIfPresent(key);
        if (hit != null) return hit;
        // L2 Redis：P0 用 JSON 序列化整条结果
        try {
            String json = redisTemplate.opsForValue().get(KEY_PREFIX + key);
            if (json != null) {
                TableResult result = TableResultJsonCodec.decode(json);
                l1.put(key, result);
                return result;
            }
        } catch (Exception e) {
            log.warn("结果缓存读取失败（降级直查）: {}", e.getMessage());
        }
        return null;
    }

    public void put(String key, TableResult result, int ttlSeconds) {
        l1.put(key, result);
        try {
            String json = TableResultJsonCodec.encode(result);
            if (json.getBytes().length > MAX_CACHE_BYTES) return;
            redisTemplate.opsForValue().set(KEY_PREFIX + key, json, Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            log.warn("结果缓存写入失败（不影响主链路）: {}", e.getMessage());
        }
    }

    /** capability 变更/上下线时按 id 前缀失效 */
    public void evictByCapability(String capabilityId) {
        l1.invalidateAll();
        try {
            var keys = redisTemplate.keys(KEY_PREFIX + capabilityId + "|*");
            if (keys != null && !keys.isEmpty()) redisTemplate.delete(keys);
        } catch (Exception e) {
            log.warn("缓存失效失败: {}", e.getMessage());
        }
    }
}
