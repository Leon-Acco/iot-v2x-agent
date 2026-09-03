-- V2 Phase 1: governance tables (capability version history / slow query log / agent trace)
CREATE TABLE IF NOT EXISTS capability_definition_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(32) NOT NULL DEFAULT 'T1',
    capability_id VARCHAR(128) NOT NULL,
    version INT NOT NULL,
    snapshot TEXT NOT NULL,
    status VARCHAR(16),
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_cap_ver (capability_id, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS query_slow_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trace_id VARCHAR(64),
    capability_id VARCHAR(128),
    sql_fingerprint VARCHAR(128),
    duration_ms BIGINT,
    row_count INT,
    max_rows INT,
    tenant_id VARCHAR(32),
    username VARCHAR(64),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agent_trace (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trace_id VARCHAR(64) NOT NULL,
    span_type VARCHAR(32) NOT NULL,
    name VARCHAR(128),
    start_ms BIGINT,
    duration_ms BIGINT,
    status VARCHAR(16),
    detail TEXT,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    KEY idx_trace (trace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
