package com.dst.v2xagent.memory;

import com.dst.v2xagent.memory.model.Memory;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 情景记忆存储（MySQL agent_memory_episode）
 * 只保存任务 / 摘要 / 实体 / 结果引用，不保存完整上下文。
 */
@Slf4j
@Repository
public class EpisodeMemoryStore {

    private final JdbcTemplate controlJdbcTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    public EpisodeMemoryStore(@Qualifier("controlJdbcTemplate") JdbcTemplate controlJdbcTemplate) {
        this.controlJdbcTemplate = controlJdbcTemplate;
    }

    /** 写入情景记忆 */
    public Long insert(Memory memory, Long userId, String sessionId) {
        try {
            controlJdbcTemplate.update(
                    "INSERT INTO agent_memory_episode (tenant_id, user_id, session_id, task_type, summary, entities, result_refs, importance)"
                    + " VALUES (?,?,?,?,?,?,?,?)",
                    memory.tenantId(), userId, sessionId, memory.memoryType(), memory.content(),
                    toJson(memory.entities()), toJson(memory.resultRefs()), memory.importance());
            Long id = controlJdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            return id;
        } catch (Exception e) {
            log.warn("情景记忆写入失败: {}", e.getMessage());
            return null;
        }
    }

    /** 最近情景记忆（用户维度，时间倒序） */
    public List<Memory> recent(String tenantId, Long userId, int limit) {
        return controlJdbcTemplate.query(
                "SELECT * FROM agent_memory_episode WHERE tenant_id = ? AND user_id = ?"
                + " ORDER BY id DESC LIMIT ?",
                (rs, i) -> rowToMemory(rs), tenantId, userId, Math.max(1, Math.min(limit, 50)));
    }

    /** 重要情景记忆（重要度倒序） */
    public List<Memory> important(String tenantId, Long userId, int limit) {
        return controlJdbcTemplate.query(
                "SELECT * FROM agent_memory_episode WHERE tenant_id = ? AND user_id = ?"
                + " ORDER BY importance DESC, id DESC LIMIT ?",
                (rs, i) -> rowToMemory(rs), tenantId, userId, Math.max(1, Math.min(limit, 50)));
    }

    /** 删除 */
    public void delete(Long id) {
        controlJdbcTemplate.update("DELETE FROM agent_memory_episode WHERE id = ?", id);
    }

    private Memory rowToMemory(java.sql.ResultSet rs) throws java.sql.SQLException {
        try {
            return new Memory(rs.getLong("id"), "EPISODE", rs.getString("tenant_id"),
                    "USER", String.valueOf(rs.getLong("user_id")),
                    rs.getString("task_type"), rs.getString("summary"),
                    rs.getDouble("importance"), 1.0, "episode",
                    mapper.readValue(rs.getString("result_refs") == null ? "[]" : rs.getString("result_refs"),
                            mapper.getTypeFactory().constructCollectionType(List.class, String.class)),
                    mapper.readValue(rs.getString("entities") == null ? "{}" : rs.getString("entities"), Map.class),
                    rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toLocalDateTime());
        } catch (java.sql.SQLException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("情景记忆行解析失败", e);
        }
    }

    private String toJson(Object o) {
        try {
            return o == null ? null : mapper.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }
}
