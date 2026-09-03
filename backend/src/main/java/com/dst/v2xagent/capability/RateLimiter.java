package com.dst.v2xagent.capability;

import org.springframework.stereotype.Component;

import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 简单滑动窗口限流器（内存版）：capability × 租户维度，防 Agent 失控刷库
 * 单实例内存实现，多实例部署时可换 Redis 实现（接口不变）
 */
@Component
public class RateLimiter {

    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    /**
     * 尝试获取一次调用配额
     * @param key            限流键（capability|tenant）
     * @param limitPerMinute 每分钟上限；<= 0 表示不限流
     * @return true=放行，false=触发限流
     */
    public boolean tryAcquire(String key, int limitPerMinute) {
        if (limitPerMinute <= 0) {
            return true;
        }
        long now = System.currentTimeMillis();
        Deque<Long> q = windows.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > 60_000) {
                q.pollFirst();
            }
            if (q.size() >= limitPerMinute) {
                return false;
            }
            q.addLast(now);
            return true;
        }
    }
}
