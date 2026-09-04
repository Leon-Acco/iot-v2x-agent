-- ============================================================
-- V18: capability_proposal (approval workflow for capabilities)
-- AI-generated / manually created capabilities must be approved before
-- going online. Mirrors the memory_proposal pattern.
-- ============================================================
CREATE TABLE IF NOT EXISTS capability_proposal (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id      VARCHAR(64)  NOT NULL DEFAULT 'T1',
    capability_id  VARCHAR(64)  NOT NULL COMMENT 'target capability id',
    action         VARCHAR(16)  NOT NULL COMMENT 'create / online',
    payload        MEDIUMTEXT   NULL COMMENT 'capability definition snapshot (JSON)',
    proposed_by    VARCHAR(64)  NOT NULL,
    status         VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPROVED/REJECTED',
    reject_reason  VARCHAR(512) NULL,
    reviewed_by    VARCHAR(64)  NULL,
    reviewed_at    DATETIME(3)  NULL,
    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_cap (capability_id),
    KEY idx_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
