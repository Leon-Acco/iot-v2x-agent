-- ============================================================
-- V7: 允许 sql_template 为 NULL（编排引用型 capability 不承载 SQL）
-- ============================================================
ALTER TABLE capability_definition MODIFY COLUMN sql_template TEXT NULL;
