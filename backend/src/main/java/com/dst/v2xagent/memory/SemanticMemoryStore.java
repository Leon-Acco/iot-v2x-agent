package com.dst.v2xagent.memory;

import com.dst.v2xagent.memory.model.Memory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 语义记忆存储（MySQL agent_semantic_memory，FULLTEXT 检索）
 * 车辆 / 车队 / 用户 / 全局长期沉淀：subject_type = VEHICLE / FLEET / USER / GLOBAL。
 * 向量字段预留（embedding），当前用 FULLTEXT 作为语义召回近似。
 */
@Slf4j
@Repository
public class SemanticMemoryStore {

    private final JdbcTemplate controlJdbcTemplate;

    public SemanticMemoryStore(@Qualifier("controlJdbcTemplate") JdbcTemplate controlJdbcTemplate) {
        this.controlJdbcTemplate = controlJdbcTemplate;
    }

    /** 写入语义记忆 */
    public Long insert(Memory memory) {
        try {
            controlJdbcTemplate.update(
                    "INSERT INTO agent_semantic_memory (tenant_id, subject_type, subject_id, memory_type, content, importance, confidence, source)"
                    + " VALUES (?,?,?,?,?,?,?,?)",
                    memory.tenantId(), memory.subjectType(), memory.subjectId(), memory.memoryType(),
                    memory.content(), memory.importance(), memory.confidence(), memory.source());
            return controlJdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        } catch (Exception e) {
            log.warn("语义记忆写入失败: {}", e.getMessage());
            return null;
        }
    }

    /** 更新内容与置信度 */
    public void update(Memory memory) {
        controlJdbcTemplate.update(
                "UPDATE agent_semantic_memory SET content = ?, importance = ?, confidence = ? WHERE id = ?",
                memory.content(), memory.importance(), memory.confidence(), memory.id());
    }

    /** 主体精确召回（如某车队的全部沉淀） */
    public List<Memory> bySubject(String tenantId, String subjectType, String subjectId, int limit) {
        return controlJdbcTemplate.query(
                "SELECT * FROM agent_semantic_memory WHERE tenant_id = ? AND subject_type = ? AND subject_id = ?"
                + " ORDER BY importance DESC, id DESC LIMIT ?",
                (rs, i) -> rowToMemory(rs), tenantId, subjectType, subjectId, Math.max(1, Math.min(limit, 50)));
    }

    /** 最近召回 */
    public List<Memory> recent(String tenantId, String subjectType, int limit) {
        if (subjectType == null) {
            return controlJdbcTemplate.query(
                    "SELECT * FROM agent_semantic_memory WHERE tenant_id = ? ORDER BY id DESC LIMIT ?",
                    (rs, i) -> rowToMemory(rs), tenantId, Math.max(1, Math.min(limit, 50)));
        }
        return controlJdbcTemplate.query(
                "SELECT * FROM agent_semantic_memory WHERE tenant_id = ? AND subject_type = ? ORDER BY id DESC LIMIT ?",
                (rs, i) -> rowToMemory(rs), tenantId, subjectType, Math.max(1, Math.min(limit, 50)));
    }

    /** 重要度召回 */
    public List<Memory> important(String tenantId, String subjectType, int limit) {
        if (subjectType == null) {
            return controlJdbcTemplate.query(
                    "SELECT * FROM agent_semantic_memory WHERE tenant_id = ? ORDER BY importance DESC, id DESC LIMIT ?",
                    (rs, i) -> rowToMemory(rs), tenantId, Math.max(1, Math.min(limit, 50)));
        }
        return controlJdbcTemplate.query(
                "SELECT * FROM agent_semantic_memory WHERE tenant_id = ? AND subject_type = ?"
                + " ORDER BY importance DESC, id DESC LIMIT ?",
                (rs, i) -> rowToMemory(rs), tenantId, subjectType, Math.max(1, Math.min(limit, 50)));
    }

    /** 全文召回（语义近似，BOOLEAN MODE） */
    public List<Memory> fulltext(String tenantId, String subjectType, String text, int limit) {
        String booleanQuery = toBooleanQuery(text);
        if (booleanQuery.isBlank()) {
            return List.of();
        }
        if (subjectType == null) {
            return controlJdbcTemplate.query(
                    "SELECT *, MATCH(content) AGAINST (? IN BOOLEAN MODE) AS score FROM agent_semantic_memory"
                    + " WHERE tenant_id = ? AND MATCH(content) AGAINST (? IN BOOLEAN MODE)"
                    + " ORDER BY score DESC LIMIT ?",
                    (rs, i) -> rowToMemory(rs), booleanQuery, tenantId, booleanQuery,
                    Math.max(1, Math.min(limit, 50)));
        }
        return controlJdbcTemplate.query(
                "SELECT *, MATCH(content) AGAINST (? IN BOOLEAN MODE) AS score FROM agent_semantic_memory"
                + " WHERE tenant_id = ? AND subject_type = ? AND MATCH(content) AGAINST (? IN BOOLEAN MODE)"
                + " ORDER BY score DESC LIMIT ?",
                (rs, i) -> rowToMemory(rs), booleanQuery, tenantId, subjectType, booleanQuery,
                Math.max(1, Math.min(limit, 50)));
    }

    /** 删除 */
    public void delete(Long id) {
        controlJdbcTemplate.update("DELETE FROM agent_semantic_memory WHERE id = ?", id);
    }

    /** 关键词转 BOOLEAN MODE：按空白拆分加 + 前缀，过滤特殊符号 */
    private String toBooleanQuery(String text) {
        if (text == null) {
            return "";
        }
        String[] tokens = text.replaceAll("[+-><()~*'"@]+", " ").trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String t : tokens) {
            if (t.length() >= 2) {
                sb.append("+").append(t).append(" ");
            }
        }
        return sb.toString().trim();
    }

    private Memory rowToMemory(java.sql.ResultSet rs) throws java.sql.SQLException {
        String subjectType = rs.getString("subject_type");
        String layer = switch (subjectType) {
            case "VEHICLE" -> "VEHICLE";
            case "FLEET" -> "FLEET";
            default -> "SEMANTIC";
        };
        return new Memory(rs.getLong("id"), layer, rs.getString("tenant_id"),
                subjectType, rs.getString("subject_id"), rs.getString("memory_type"),
                rs.getString("content"), rs.getDouble("importance"), rs.getDouble("confidence"),
                rs.getString("source"), List.of(), java.util.Map.of(),
                rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toLocalDateTime());
    }
}
