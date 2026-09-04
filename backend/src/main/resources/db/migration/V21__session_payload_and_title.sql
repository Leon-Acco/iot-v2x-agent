-- V21: 会话标题 + 轮次完整帧快照（会话恢复 / 导出 PDF 数据源）
-- agent_session 加 title（首轮问题自动命名）与 turn_count，加用户维度列表索引
ALTER TABLE agent_session
    ADD COLUMN title VARCHAR(128) NULL COMMENT '会话标题（首轮问题截断）' AFTER profile_id,
    ADD COLUMN turn_count INT NOT NULL DEFAULT 0 COMMENT '累计轮次' AFTER title,
    ADD KEY idx_user_updated (tenant_id, user_id, profile_id, updated_at);

-- agent_session_message 加 payload_json：完整帧快照（answer/tools/result/chart/visualizations/followUps/traceId/forcedTool）
ALTER TABLE agent_session_message
    ADD COLUMN payload_json JSON NULL COMMENT '完整帧快照，会话恢复与导出数据源' AFTER conclusion_summary;
