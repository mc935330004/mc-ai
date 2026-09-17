-- 第七阶段：补充 WRITE 操作确认所需的冻结字段。
-- 已存在的待确认记录不会继续执行，由业务层在确认时拒绝缺少冻结信息的旧记录。
ALTER TABLE ai_pending_action
    ADD COLUMN capability_version_id BIGINT DEFAULT NULL
    COMMENT '创建预览时冻结的能力发布版本ID'
        AFTER capability_name,
    ADD COLUMN capability_config_checksum CHAR(64) DEFAULT NULL
        COMMENT '创建预览时冻结的能力配置SHA-256'
        AFTER capability_version_id,
    ADD COLUMN input_digest CHAR(64) DEFAULT NULL
        COMMENT '固定操作参数JSON的SHA-256'
        AFTER input_json;

-- 增加拒绝执行和执行结果不确定状态。
ALTER TABLE ai_pending_action
    MODIFY COLUMN status VARCHAR(32) NOT NULL DEFAULT 'PENDING'
    COMMENT 'PENDING/CONFIRMED/EXECUTING/SUCCESS/FAILED/UNKNOWN/REJECTED/CANCELLED/EXPIRED';
