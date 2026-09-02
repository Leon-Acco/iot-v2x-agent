-- ============================================================
-- V13: A2A 授权审批（调用方 Agent 注册 + credential 签发）
-- ============================================================
CREATE TABLE IF NOT EXISTS a2a_agent (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id        VARCHAR(64)  NOT NULL DEFAULT 'T1',
    agent_name       VARCHAR(128) NOT NULL,
    description      VARCHAR(512) NULL,
    callback_url     VARCHAR(512) NULL,
    requested_scopes JSON         NULL COMMENT '申请的角色与 scope',
    granted_roles    JSON         NULL COMMENT '批准的角色',
    granted_scopes   JSON         NULL COMMENT '批准的 scope 白名单',
    status           VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED/REVOKED',
    review_note      VARCHAR(512) NULL,
    reviewed_by      VARCHAR(64)  NULL,
    reviewed_at      DATETIME(3)  NULL,
    created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_tenant_status (tenant_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS a2a_credential (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id    VARCHAR(64)  NOT NULL DEFAULT 'T1',
    agent_id     BIGINT       NOT NULL,
    client_id    VARCHAR(64)  NOT NULL,
    secret_hash  CHAR(64)     NOT NULL COMMENT 'SHA-256 hex，明文仅下发时展示一次',
    status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/REVOKED',
    expires_at   DATETIME(3)  NULL,
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_client (client_id),
    KEY idx_agent (agent_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
