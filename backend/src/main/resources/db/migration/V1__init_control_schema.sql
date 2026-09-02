-- ============================================================
-- V1: 车联网平台 Agent 控制库初始化（P0）
-- 统一约定：多租户表 tenant_id 前导；时间 UTC DATETIME(3)；乐观锁 version
-- ============================================================

-- 用户表（P0 本地账号密码，种子用户对应不同车队权限）
CREATE TABLE IF NOT EXISTS sys_user (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id    VARCHAR(64)  NOT NULL DEFAULT 'T1',
    username     VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(128) NOT NULL,
    display_name VARCHAR(64)  NOT NULL,
    roles        JSON         NOT NULL COMMENT '角色集合，如 ["fleet_operator","admin"]',
    fleet_ids    JSON         NOT NULL COMMENT '可见车队集合',
    created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_username (tenant_id, username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 登录会话（HttpOnly Cookie token）
CREATE TABLE IF NOT EXISTS user_session (
    token       VARCHAR(64) NOT NULL,
    user_id     BIGINT      NOT NULL,
    expires_at  DATETIME(3) NOT NULL,
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (token),
    KEY idx_user (user_id),
    KEY idx_expires (expires_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- capability 元数据（单一事实源：模型 Schema / 校验器 / 后台表单 / 评测格式均由其生成）
CREATE TABLE IF NOT EXISTS capability_definition (
    id                VARCHAR(64)  NOT NULL COMMENT '能力 id，不可变',
    tenant_id         VARCHAR(64)  NOT NULL DEFAULT 'T1',
    display           VARCHAR(128) NOT NULL COMMENT '中文显示名',
    description       TEXT         NULL COMMENT '给模型看的能力描述',
    aliases           JSON         NULL COMMENT '别名：召回准确率主力',
    domain            VARCHAR(32)  NOT NULL COMMENT '域：online/location/mileage/alarm/...',
    readonly          TINYINT(1)   NOT NULL DEFAULT 1,
    params            JSON         NOT NULL COMMENT '参数定义数组',
    returns_meta      JSON         NOT NULL COMMENT '返回结构（shape/columns 语义类型）',
    chart_hint        VARCHAR(32)  NULL,
    source_tables     JSON         NULL COMMENT '依赖的 Doris 表（时效水位用）',
    freshness_policy  JSON         NULL COMMENT 'type/expected_delay_min',
    limits            JSON         NOT NULL COMMENT 'max_rows/timeout_ms/max_span_days/max_qps',
    cache             JSON         NULL COMMENT 'ttl_seconds/cacheable',
    scopes            JSON         NOT NULL COMMENT '所需 scope，如 vehicle.alarm.read',
    row_filter_policy VARCHAR(32)  NOT NULL COMMENT '行级权限注入方式：by_fleet/by_vin/by_org',
    sample_questions  JSON         NULL COMMENT '示例问题（召回语料 + 前端空状态）',
    sql_template      MEDIUMTEXT   NOT NULL COMMENT 'SQL 模板，必须含 ACL 占位符',
    status            VARCHAR(16)  NOT NULL DEFAULT 'draft' COMMENT 'draft/staging/online/deprecated',
    version           INT          NOT NULL DEFAULT 1,
    owner             VARCHAR(64)  NULL,
    created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (tenant_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 会话（threadId → sessionId 映射）
CREATE TABLE IF NOT EXISTS agent_session (
    id          VARCHAR(64) NOT NULL COMMENT 'sessionId',
    tenant_id   VARCHAR(64) NOT NULL,
    thread_id   VARCHAR(64) NOT NULL,
    user_id     BIGINT      NOT NULL,
    profile_id  VARCHAR(32) NOT NULL COMMENT 'device_ops / data_base',
    version     BIGINT      NOT NULL DEFAULT 0 COMMENT '乐观锁',
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_thread_user_profile (tenant_id, thread_id, user_id, profile_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 会话轮次（完整留存；进 prompt 的只是摘要）
CREATE TABLE IF NOT EXISTS agent_session_message (
    id                 BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id          VARCHAR(64) NOT NULL,
    session_id         VARCHAR(64) NOT NULL,
    role               VARCHAR(16) NOT NULL COMMENT 'user/assistant',
    content            TEXT        NULL COMMENT '提问原文 / 结论文本',
    capability_id      VARCHAR(64) NULL,
    params_json        JSON        NULL COMMENT '归一化后的参数（摘要）',
    row_count          INT         NULL,
    conclusion_summary VARCHAR(512) NULL COMMENT '关键结论 3 行摘要',
    created_at         DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_session (tenant_id, session_id, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 运行审计（每次 run 一条，append-only）
CREATE TABLE IF NOT EXISTS run_audit (
    id             BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id      VARCHAR(64) NOT NULL,
    run_id         VARCHAR(64) NOT NULL,
    trace_id       VARCHAR(64) NOT NULL,
    user_id        BIGINT      NOT NULL,
    profile_id     VARCHAR(32) NOT NULL,
    question       TEXT        NULL,
    capability_id  VARCHAR(64) NULL,
    params_json    JSON        NULL,
    status         VARCHAR(16) NOT NULL COMMENT 'SUCCESS/REFUSED/FAILED/CANCELLED',
    error_code     VARCHAR(32) NULL,
    failed_stage   VARCHAR(16) NULL,
    elapsed_ms     BIGINT      NULL,
    row_count      INT         NULL,
    prompt_version VARCHAR(16) NULL,
    model_id       VARCHAR(64) NULL,
    created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_run (tenant_id, run_id),
    KEY idx_trace (trace_id),
    KEY idx_created (tenant_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 用户反馈（👍👎，喂给 Bad Case 工作台）
CREATE TABLE IF NOT EXISTS feedback (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    tenant_id  VARCHAR(64) NOT NULL,
    run_id     VARCHAR(64) NOT NULL,
    trace_id   VARCHAR(64) NULL,
    user_id    BIGINT      NOT NULL,
    rating     TINYINT     NOT NULL COMMENT '1=👍 -1=👎',
    comment    TEXT        NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_run (tenant_id, run_id),
    KEY idx_created (tenant_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
