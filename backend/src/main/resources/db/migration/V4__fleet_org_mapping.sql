-- ============================================================
-- V4: 车队 → Doris 组织（org_name）映射（真实数据 ACL 键）
-- 用户 fleetIds 经过本表映射为 basic_vehicle_info.org_name；
-- 未命中映射的 token 原样透传（兼容直接使用 org 名称）
-- ============================================================
CREATE TABLE IF NOT EXISTS fleet_org_mapping (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    tenant_id   VARCHAR(64)  NOT NULL DEFAULT 'T1',
    fleet_id    VARCHAR(64)  NOT NULL COMMENT '用户侧车队标识（F001 等）',
    org_name    VARCHAR(255) NOT NULL COMMENT 'Doris basic_vehicle_info.org_name',
    display     VARCHAR(128) NOT NULL COMMENT '展示名',
    created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_fleet (tenant_id, fleet_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

INSERT INTO fleet_org_mapping (tenant_id, fleet_id, org_name, display) VALUES
('T1', 'F001', '深圳市地上铁新能源汽车运营有限公司', '车队一（地上铁运营）'),
('T1', 'F002', '广州新创绿能新能源汽车服务有限公司', '车队二（新创绿能）'),
('T1', 'F003', '深圳市墨朗汽车有限公司', '车队三（墨朗汽车）');
