-- ============================================================
-- V5: 清空 capability_definition 重新种子（yaml 已切换为真实 Doris 表）
-- 启动时 seedFromYaml 会重新插入全部 capability 与编排引用
-- ============================================================
DELETE FROM capability_definition WHERE tenant_id = 'T1';
