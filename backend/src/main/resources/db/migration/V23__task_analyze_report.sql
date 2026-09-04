-- ============================================================
-- V23: 任务卡 2.0 补充——分析报告开关与报告落库
-- 任务执行（手动/定时）查完步骤后，可用分析模型（LLM）对多步
-- 查询结果生成分析报告（用户要求：任务要利用分析工具做处理生成）
-- ============================================================

ALTER TABLE task_definition
    ADD COLUMN analyze_report TINYINT NOT NULL DEFAULT 1
        COMMENT '执行后是否用分析模型生成报告 0/1' AFTER timeout_seconds;

ALTER TABLE task_run
    ADD COLUMN conclusion TEXT NULL
        COMMENT '分析报告（LLM 基于本次各步结果生成；生成失败为 NULL 不影响状态）' AFTER steps_result_json;
