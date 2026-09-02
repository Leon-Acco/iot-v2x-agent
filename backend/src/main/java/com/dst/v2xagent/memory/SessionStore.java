package com.dst.v2xagent.memory;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 会话持久化（MySQL 事实库，不用本地文件）
 * (threadId, userId, profileId) 唯一确定一个 sessionId；
 * 完整轮次落库，进 prompt 的只是摘要。
 */
@Repository
@RequiredArgsConstructor
public class SessionStore {

    private final JdbcTemplate controlJdbcTemplate;

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

    /** 记录一轮（含失败轮次，便于复盘） */
    public void appendTurn(String tenantId, String sessionId, String question, String capabilityId,
                           String paramsJson, Integer rowCount, String conclusion, String conclusionSummary) {
        controlJdbcTemplate.update("""
                INSERT INTO agent_session_message
                (tenant_id, session_id, role, content, capability_id, params_json, row_count, conclusion_summary)
                VALUES (?, ?, 'user', ?, NULL, NULL, NULL, NULL)
                """, tenantId, sessionId, question);
        controlJdbcTemplate.update("""
                INSERT INTO agent_session_message
                (tenant_id, session_id, role, content, capability_id, params_json, row_count, conclusion_summary)
                VALUES (?, ?, 'assistant', ?, ?, ?, ?, ?)
                """, tenantId, sessionId, conclusion, capabilityId, paramsJson, rowCount, conclusionSummary);
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
}
