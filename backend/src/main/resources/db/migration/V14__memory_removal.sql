-- ============================================================
-- V14: 记忆删除申请（P6 记忆治理台）
-- 删除不直接生效：先申请，管理员处理后决定删除或保留
-- ============================================================
CREATE TABLE IF NOT EXISTS memory_removal_request (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id    VARCHAR(64)  NOT NULL DEFAULT 'T1',
    memory_id    BIGINT       NOT NULL,
    reason       VARCHAR(512) NOT NULL,
    requested_by VARCHAR(64)  NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/DELETED/KEPT',
    resolved_by  VARCHAR(64)  NULL,
    resolved_at  DATETIME(3)  NULL,
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_tenant_status (tenant_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
