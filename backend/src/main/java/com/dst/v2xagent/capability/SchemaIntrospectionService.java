package com.dst.v2xagent.capability;

import com.dst.v2xagent.common.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分析库表结构自省服务
 * 供 AI 生成能力与管理后台选表使用：
 * 从 information_schema 读取当前分析库（Doris/MySQL）的表与列元数据，5 分钟缓存。
 */
@Slf4j
@Component
public class SchemaIntrospectionService {

    /** 缓存有效期：5 分钟 */
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;

    private final JdbcTemplate analyticsJdbcTemplate;

    /** 显式注入分析库 JdbcTemplate（双数据源强隔离，与 QueryExecutor 同源） */
    public SchemaIntrospectionService(@Qualifier("analyticsJdbcTemplate") JdbcTemplate analyticsJdbcTemplate) {
        this.analyticsJdbcTemplate = analyticsJdbcTemplate;
    }

    private volatile CacheEntry tablesCache;
    private final Map<String, CacheEntry> columnsCache = new ConcurrentHashMap<>();

    /** 全部表：table_name / table_comment */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listTables() {
        CacheEntry hit = tablesCache;
        if (hit != null && hit.fresh()) {
            return (List<Map<String, Object>>) hit.value();
        }
        List<Map<String, Object>> tables = analyticsJdbcTemplate.queryForList(
                "SELECT TABLE_NAME AS table_name, TABLE_COMMENT AS table_comment"
                + " FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_NAME");
        tablesCache = new CacheEntry(tables, System.currentTimeMillis());
        return tables;
    }

    /** 指定表的列：column_name / column_type / is_nullable / column_comment；表名白名单校验防注入 */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listColumns(String table) {
        if (table == null || !table.matches("[a-zA-Z0-9_]{1,64}")) {
            throw ApiException.paramInvalid("非法表名: " + table);
        }
        CacheEntry hit = columnsCache.get(table);
        if (hit != null && hit.fresh()) {
            return (List<Map<String, Object>>) hit.value();
        }
        List<Map<String, Object>> columns = analyticsJdbcTemplate.queryForList(
                "SELECT COLUMN_NAME AS column_name, COLUMN_TYPE AS column_type,"
                + " IS_NULLABLE AS is_nullable, COLUMN_COMMENT AS column_comment"
                + " FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?"
                + " ORDER BY ORDINAL_POSITION", table);
        columnsCache.put(table, new CacheEntry(columns, System.currentTimeMillis()));
        return columns;
    }

    /** 供 prompt 使用的紧凑文本：表(注释): 列 类型 注释, ... */
    public String describeForPrompt(List<String> tables) {
        StringBuilder sb = new StringBuilder();
        for (String t : tables) {
            List<Map<String, Object>> cols = listColumns(t);
            String comment = listTables().stream()
                    .filter(row -> t.equals(String.valueOf(row.get("table_name"))))
                    .map(row -> String.valueOf(row.get("table_comment")))
                    .findFirst().orElse("");
            sb.append("- ").append(t);
            if (comment != null && !comment.isBlank() && !"null".equals(comment)) {
                sb.append(" (").append(comment).append(")");
            }
            sb.append(":\n");
            for (Map<String, Object> c : cols) {
                sb.append("    ").append(c.get("column_name")).append(" ").append(c.get("column_type"));
                Object cc = c.get("column_comment");
                if (cc != null && !String.valueOf(cc).isBlank()) {
                    sb.append("  # ").append(cc);
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    /** 简易缓存项 */
    private record CacheEntry(Object value, long loadedAt) {
        boolean fresh() {
            return System.currentTimeMillis() - loadedAt < CACHE_TTL_MS;
        }
    }
}
