-- ============================================================
-- V3: 组织长记忆（P1 MemoryGateway）
-- MySQL 是唯一事实库；召回先 ACL 预过滤，候选必须二次验权（fail closed）
-- ============================================================

-- 记忆主表：正文 + 来源证据 + 版本 + 失效时间
CREATE TABLE IF NOT EXISTS memory_item (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       VARCHAR(64)  NOT NULL DEFAULT 'T1',
    scope_type      VARCHAR(32)  NOT NULL COMMENT '范围：vehicle / model / fleet / global',
    scope_key       VARCHAR(128) NOT NULL DEFAULT '' COMMENT '范围键：vin/车型/车队id/空',
    content         TEXT         NOT NULL COMMENT '记忆正文',
    content_index   TEXT         NOT NULL COMMENT '召回索引文本（ngram 分词）',
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / ARCHIVED',
    source_trace_id VARCHAR(64)  NULL COMMENT '来源 traceId（可追溯）',
    proposed_by     VARCHAR(64)  NOT NULL,
    approved_by     VARCHAR(64)  NULL,
    evidence        JSON         NULL COMMENT '当时 capability + 参数 + 关键数值',
    source_version  BIGINT       NOT NULL DEFAULT 1,
    expire_at       DATETIME(3)  NULL COMMENT '默认 180 天，到期进复审',
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_tenant_status (tenant_id, status),
    FULLTEXT KEY ft_content (content_index) WITH PARSER ngram
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 记忆 ACL：principal_token 形式 user:1 / role:operator / fleet:F001
CREATE TABLE IF NOT EXISTS memory_acl (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       VARCHAR(64)  NOT NULL DEFAULT 'T1',
    memory_id       BIGINT       NOT NULL,
    principal_token VARCHAR(128) NOT NULL,
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_memory_principal (memory_id, principal_token),
    KEY idx_principal (tenant_id, principal_token)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 记忆写入提议（只能提议，不能直插；管理员审核后生效）
CREATE TABLE IF NOT EXISTS memory_proposal (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       VARCHAR(64)  NOT NULL DEFAULT 'T1',
    scope_type      VARCHAR(32)  NOT NULL,
    scope_key       VARCHAR(128) NOT NULL DEFAULT '',
    content         TEXT         NOT NULL,
    source_trace_id VARCHAR(64)  NULL,
    proposed_by     VARCHAR(64)  NOT NULL,
    evidence        JSON         NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / APPROVED / REJECTED',
    reject_reason   VARCHAR(512) NULL,
    reviewed_by     VARCHAR(64)  NULL,
    reviewed_at     DATETIME(3)  NULL,
    expire_at       DATETIME(3)  NULL,
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_tenant_status (tenant_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
