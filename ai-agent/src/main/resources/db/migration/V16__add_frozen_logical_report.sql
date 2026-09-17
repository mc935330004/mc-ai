-- ============================================================
-- 为组合报告任务增加冻结逻辑报告。
--
-- 设计约束：
-- 1. source_run_id 关联产生当前报告的回答运行；
-- 2. content_version 校验逻辑报告、来源快照和字段策略没有混用；
-- 3. logical_report_json 只保存经过 exportAllowed 过滤后的安全报告；
-- 4. 报告过期后清除冻结内容，不长期保留业务数据。
-- ============================================================

ALTER TABLE ai_composite_report_task
    ADD COLUMN source_run_id VARCHAR(64) NULL
        COMMENT '产生当前冻结报告的回答运行ID'
        AFTER data_complete,
    ADD COLUMN content_version CHAR(64) NULL
        COMMENT '逻辑报告、来源快照和字段策略的SHA-256内容版本'
        AFTER source_run_id,
    ADD COLUMN logical_report_json JSON NULL
        COMMENT '经过导出字段策略过滤后的冻结逻辑报告'
        AFTER content_version,
    ADD COLUMN frozen_at DATETIME(3) NULL
        COMMENT '逻辑报告冻结时间'
        AFTER logical_report_json;
