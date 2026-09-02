-- ============================================================
-- V6: 任务卡（R1 草稿级：不可变导出快照 + PDF 下载记录）
-- 导出创建时固化 capabilityVersion/参数/权限指纹/数据水位（设计文档 §11.3）
-- ============================================================
CREATE TABLE IF NOT EXISTS task_card (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id       VARCHAR(64)  NOT NULL DEFAULT 'T1',
    run_id          VARCHAR(64)  NULL,
    trace_id        VARCHAR(64)  NULL,
    title           VARCHAR(255) NOT NULL,
    capability_id   VARCHAR(64)  NOT NULL,
    capability_version INT       NOT NULL,
    params_json     JSON         NOT NULL COMMENT '归一化参数',
    snapshot_json   JSON         NOT NULL COMMENT '不可变导出快照（表格前 N 行 + 结论 + 时效）',
    permission_fingerprint VARCHAR(128) NOT NULL COMMENT '权限指纹（下载时复核）',
    file_path       VARCHAR(512) NOT NULL COMMENT 'PDF 存储路径',
    status          VARCHAR(16)  NOT NULL DEFAULT 'READY' COMMENT 'READY / EXPIRED / DELETED',
    created_by      VARCHAR(64)  NOT NULL,
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_tenant_creator (tenant_id, created_by)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
