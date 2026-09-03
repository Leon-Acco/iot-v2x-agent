-- V2 Phase 3: episode memory + semantic memory (vehicle/fleet/user/global)
CREATE TABLE IF NOT EXISTS agent_memory_episode (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(32) NOT NULL DEFAULT 'T1',
    user_id BIGINT,
    session_id VARCHAR(64),
    task_type VARCHAR(64),
    summary TEXT,
    entities TEXT,
    result_refs TEXT,
    importance DOUBLE DEFAULT 0.5,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_user (tenant_id, user_id, created_at),
    KEY idx_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agent_semantic_memory (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(32) NOT NULL DEFAULT 'T1',
    subject_type VARCHAR(32) NOT NULL,
    subject_id VARCHAR(128),
    memory_type VARCHAR(32) NOT NULL,
    content TEXT NOT NULL,
    importance DOUBLE DEFAULT 0.5,
    confidence DOUBLE DEFAULT 0.8,
    source VARCHAR(64),
    embedding TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FULLTEXT KEY ft_content (content),
    KEY idx_subject (tenant_id, subject_type, subject_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
