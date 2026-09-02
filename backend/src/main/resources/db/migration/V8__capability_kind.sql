-- ============================================================
-- V8: capability_definition 增加 kind 列（区分单能力与编排引用）
-- ============================================================
ALTER TABLE capability_definition ADD COLUMN kind VARCHAR(32) NOT NULL DEFAULT 'capability' AFTER id;
UPDATE capability_definition SET kind = 'orchestration' WHERE id = 'anomaly_explain';
