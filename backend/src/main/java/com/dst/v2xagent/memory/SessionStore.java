package com.dst.v2xagent.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 会话持久化（MySQL 事实库，不用本地文件）
 * (threadId, userId, profileId) 唯一确定一个 sessionId；
 * 完整轮次落库（V21 起含 payload_json 帧快照），进 prompt 的只是摘要。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class SessionStore {

    private final JdbcTemplate controlJdbcTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 解析或创建会话：切换车队上下文视为新会话（由调用方保证 threadId 变化） */
    public String resolveSessionId(String tenantId, String threadId, Long userId, String profileId) {
        List<String> found = controlJdbcTemplate.queryForList("""
                SELECT id FROM agent_session
                WHERE tenant_id = ? AND thread_id = ? AND user_id = ? AND profile_id = ?
                """, String.class, tenantId, threadId, userId, profileId);
        if (!found.isEmpty()) return found.get(0);
        String sessionId = UUID.randomUUID().toString().replace("-", "");
        controlJdbcTemplate.update("""
                INSERT INTO agent_session (id, tenant_id, thread_id, user_id, profile_id)
                VALUES (?, ?, ?, ?, ?)
                """, sessionId, tenantId, threadId, userId, profileId);
        return sessionId;
    }

    /** 记录一轮（含失败轮次，便于复盘）——旧签名兼容（无帧快照） */
    public void appendTurn(String tenantId, String sessionId, String question, String capabilityId,
                           String paramsJson, Integer rowCount, String conclusion, String conclusionSummary) {
        appendTurn(tenantId, sessionId, question, capabilityId, paramsJson, rowCount,
                conclusion, conclusionSummary, null);
    }

    /** 记录一轮（含完整帧快照 payload：tools/result/chart/visualizations/followUps/traceId/answer/forcedTool） */
    public void appendTurn(String tenantId, String sessionId, String question, String capabilityId,
                           String paramsJson, Integer rowCount, String conclusion, String conclusionSummary,
                           Map<String, Object> payload) {
        controlJdbcTemplate.update("""
                INSERT INTO agent_session_message
                (tenant_id, session_id, role, content, capability_id, params_json, row_count, conclusion_summary)
                VALUES (?, ?, 'user', ?, NULL, NULL, NULL, NULL)
                """, tenantId, sessionId, question);
        controlJdbcTemplate.update("""
                INSERT INTO agent_session_message
                (tenant_id, session_id, role, content, capability_id, params_json, row_count, conclusion_summary, payload_json)
                VALUES (?, ?, 'assistant', ?, ?, ?, ?, ?, ?)
                """, tenantId, sessionId, conclusion, capabilityId, paramsJson, rowCount, conclusionSummary,
                toJson(payload));
        controlJdbcTemplate.update("""
                UPDATE agent_session SET turn_count = turn_count + 1, updated_at = NOW(3)
                WHERE tenant_id = ? AND id = ?
                """, tenantId, sessionId);
    }

    /** 首轮自动命名（title 为空则用问题前 18 字） */
    public void touchTitleIfBlank(String tenantId, String sessionId, String question) {
        if (question == null || question.isBlank()) {
            return;
        }
        String title = question.length() > 18 ? question.substring(0, 18) : question;
        controlJdbcTemplate.update("""
                UPDATE agent_session SET title = ?
                WHERE tenant_id = ? AND id = ? AND (title IS NULL OR title = '')
                """, title, tenantId, sessionId);
    }

    /** 会话列表（本人 + 指定 profile，updated_at 倒序） */
    public List<Map<String, Object>> listSessions(String tenantId, Long userId, String profileId, int limit) {
        return controlJdbcTemplate.queryForList("""
                SELECT id sessionId, thread_id threadId, title, turn_count turnCount,
                       created_at createdAt, updated_at updatedAt
                FROM agent_session
                WHERE tenant_id = ? AND user_id = ? AND profile_id = ?
                ORDER BY updated_at DESC
                LIMIT ?
                """, tenantId, userId, profileId, limit);
    }

    /** 查询单个会话（导出/校验用；userId 非空时校验本人） */
    public Map<String, Object> findSession(String tenantId, String sessionId, Long userId) {
        List<Map<String, Object>> rows = userId == null
                ? controlJdbcTemplate.queryForList("""
                SELECT id sessionId, thread_id threadId, title, turn_count turnCount,
                       user_id userId, profile_id profileId, created_at createdAt, updated_at updatedAt
                FROM agent_session WHERE tenant_id = ? AND id = ?
                """, tenantId, sessionId)
                : controlJdbcTemplate.queryForList("""
                SELECT id sessionId, thread_id threadId, title, turn_count turnCount,
                       user_id userId, profile_id profileId, created_at createdAt, updated_at updatedAt
                FROM agent_session WHERE tenant_id = ? AND id = ? AND user_id = ?
                """, tenantId, sessionId, userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 重命名会话 */
    public void rename(String tenantId, String sessionId, Long userId, String title) {
        controlJdbcTemplate.update("""
                UPDATE agent_session SET title = ?, updated_at = updated_at
                WHERE tenant_id = ? AND id = ? AND user_id = ?
                """, title, tenantId, sessionId, userId);
    }

    /** 删除会话（session + messages 两表，物理删） */
    public void delete(String tenantId, String sessionId, Long userId) {
        controlJdbcTemplate.update("""
                DELETE FROM agent_session_message WHERE tenant_id = ? AND session_id = ?
                """, tenantId, sessionId);
        controlJdbcTemplate.update("""
                DELETE FROM agent_session WHERE tenant_id = ? AND id = ? AND user_id = ?
                """, tenantId, sessionId, userId);
    }

    /** 会话全部消息（正序，payload_json 反序列化为 Map 塞入 key "payload"） */
    public List<Map<String, Object>> listMessages(String tenantId, String sessionId) {
        List<Map<String, Object>> rows = controlJdbcTemplate.queryForList("""
                SELECT id, role, content, capability_id capabilityId, row_count rowCount,
                       conclusion_summary conclusionSummary, payload_json payloadJson, created_at createdAt
                FROM agent_session_message
                WHERE tenant_id = ? AND session_id = ?
                ORDER BY id ASC
                """, tenantId, sessionId);
        for (Map<String, Object> row : rows) {
            Object raw = row.remove("payloadJson");
            row.put("payload", fromJson(raw == null ? null : raw.toString()));
        }
        return rows;
    }

    /** 读取最近 N 轮摘要（进 prompt 用，绝不含完整结果集） */
    public List<Map<String, Object>> recentTurns(String tenantId, String sessionId, int limit) {
        return controlJdbcTemplate.queryForList("""
                SELECT role, content, capability_id, params_json, row_count, conclusion_summary
                FROM agent_session_message
                WHERE tenant_id = ? AND session_id = ?
                ORDER BY id DESC LIMIT ?
                """, tenantId, sessionId, limit);
    }

    private String toJson(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        try {
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("payload serialize failed (skip): {}", e.getMessage());
            return null;
        }
    }

    private Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }
}
