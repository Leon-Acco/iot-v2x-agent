-- ============================================================
-- V22: 任务卡 2.0（可执行任务：会话流程精炼为 capability 步骤序列，
-- 支持重复/一键/定时执行；与旧 task_card PDF 导出卡并存，互不影响）
-- 确定性重放：steps_json 只存 capabilityId + 业务参数模板
-- （相对时间词原文 / 车牌原文），执行时八步解析现算时间窗并注入 ACL，零 LLM
-- ============================================================

CREATE TABLE IF NOT EXISTS task_definition (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id           VARCHAR(64)  NOT NULL DEFAULT 'T1',
    title               VARCHAR(128) NOT NULL COMMENT '任务标题（默认取用户问题）',
    description         VARCHAR(512) NULL COMMENT '备注说明',
    source_session_id   VARCHAR(64)  NULL COMMENT '溯源会话 id（agent_session.id）',
    source_question     VARCHAR(512) NULL COMMENT '溯源用户问题原文',
    steps_json          JSON         NOT NULL COMMENT '步骤数组：[{seq,capabilityId,toolFrameId,displayName,timeMode,params}]',
    step_count          TINYINT      NOT NULL DEFAULT 1 COMMENT '步骤数（冗余，列表展示用）',
    -- 定时调度（P2 启用；P1 全部 manual）
    schedule_type       VARCHAR(16)  NOT NULL DEFAULT 'manual' COMMENT 'manual 手动 / cron 定时',
    cron_expr           VARCHAR(64)  NULL COMMENT 'cron 表达式（Asia/Shanghai 墙钟）',
    schedule_enabled    TINYINT      NOT NULL DEFAULT 0 COMMENT '定时开关 0/1',
    next_fire_at        DATETIME(3)  NULL COMMENT '下次触发时间（仅展示，恢复时由 CronTrigger 重算回写）',
    -- 执行状态滚动摘要
    last_run_id         BIGINT       NULL COMMENT '最近一次执行 task_run.id',
    last_fire_at        DATETIME(3)  NULL COMMENT '最近触发时间',
    last_status         VARCHAR(16)  NULL COMMENT 'SUCCESS / PARTIAL / FAILED / SKIPPED',
    consecutive_failures INT         NOT NULL DEFAULT 0 COMMENT '定时连续失败计数（>=3 自动暂停）',
    timeout_seconds     INT          NOT NULL DEFAULT 120 COMMENT '单次执行整体预算（秒），上限 300',
    -- 归属与生命周期
    owner_username      VARCHAR(64)  NOT NULL COMMENT '创建者（定时执行按此 username 重建 ACL）',
    status              VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE / PAUSED / ARCHIVED',
    created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_tenant_owner (tenant_id, owner_username, status),
    KEY idx_sched_scan (schedule_enabled, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS task_run (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id           VARCHAR(64)  NOT NULL DEFAULT 'T1',
    task_id             BIGINT       NOT NULL,
    trigger_type        VARCHAR(16)  NOT NULL COMMENT 'manual 手动 / cron 定时',
    triggered_by        VARCHAR(64)  NOT NULL COMMENT '触发人（手动=操作者，定时=创建者）',
    status              VARCHAR(16)  NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING / SUCCESS / PARTIAL / FAILED / SKIPPED',
    started_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    finished_at         DATETIME(3)  NULL,
    elapsed_ms          INT          NULL COMMENT '总耗时',
    permission_fingerprint VARCHAR(128) NOT NULL COMMENT '执行时权限指纹（审计：定时=创建者当时权限）',
    steps_result_json   JSON         NULL COMMENT '逐步结果摘要 + 表格前 20 行',
    error_msg           VARCHAR(1024) NULL COMMENT '整体失败原因（如创建者账号不存在）',
    PRIMARY KEY (id),
    KEY idx_task_time (tenant_id, task_id, id),
    KEY idx_tenant_started (tenant_id, started_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
