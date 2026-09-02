package com.dst.v2xagent.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查：报告控制库 / 分析库 / Redis 连通性
 */
@RestController
public class HealthController {

    private final JdbcTemplate controlJdbcTemplate;
    private final JdbcTemplate analyticsJdbcTemplate;
    private final StringRedisTemplate redisTemplate;

    public HealthController(JdbcTemplate controlJdbcTemplate,
                            @org.springframework.beans.factory.annotation.Qualifier("analyticsJdbcTemplate") JdbcTemplate analyticsJdbcTemplate,
                            StringRedisTemplate redisTemplate) {
        this.controlJdbcTemplate = controlJdbcTemplate;
        this.analyticsJdbcTemplate = analyticsJdbcTemplate;
        this.redisTemplate = redisTemplate;
    }

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UP");
        result.put("controlDb", ping(controlJdbcTemplate));
        result.put("analyticsDb", ping(analyticsJdbcTemplate));
        result.put("redis", pingRedis());
        return result;
    }

    private String ping(JdbcTemplate jdbc) {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return "UP";
        } catch (Exception e) {
            return "DOWN: " + e.getMessage();
        }
    }

    private String pingRedis() {
        try {
            String pong = redisTemplate.getConnectionFactory().getConnection().ping();
            return "PONG".equalsIgnoreCase(pong) ? "UP" : "DOWN: " + pong;
        } catch (Exception e) {
            return "DOWN: " + e.getMessage();
        }
    }
}
