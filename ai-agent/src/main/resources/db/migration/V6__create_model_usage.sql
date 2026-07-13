-- ============================================================
-- 第一阶段：新增大模型 Token 使用明细，并扩展 Agent 运行汇总字段
--
-- 风险说明：
-- 1. 这是数据库表结构变更，必须先在测试环境执行。
-- 2. 不要修改已经执行过的 V4、V5 迁移文件。
-- 3. 新字段全部提供默认值，兼容现有业务数据。
-- 4. 本阶段只统计 Token，不计算费用，避免将供应商价格硬编码。
-- ============================================================

CREATE TABLE ai_model_usage (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',

    run_id VARCHAR(64) NULL COMMENT 'Agent运行ID',
    conversation_id VARCHAR(64) NULL COMMENT '会话ID',
    user_id VARCHAR(64) NULL COMMENT '用户ID',

    call_type VARCHAR(32) NOT NULL
        COMMENT '调用类型：PLANNER、ANSWER、RAG、FIELD_SEMANTIC、REPAIR',

    call_sequence INT NOT NULL DEFAULT 1 COMMENT '相同调用类型下的调用序号',

    provider VARCHAR(64) NULL COMMENT '模型供应商',
    model_name VARCHAR(128) NULL COMMENT '模型名称',
    request_id VARCHAR(128) NULL COMMENT '模型供应商返回的请求ID',

    prompt_tokens INT NOT NULL DEFAULT 0 COMMENT '输入Token数量',
    completion_tokens INT NOT NULL DEFAULT 0 COMMENT '输出Token数量',
    total_tokens INT NOT NULL DEFAULT 0 COMMENT '总Token数量',

    cache_read_tokens BIGINT NULL COMMENT '从供应商提示词缓存读取的Token',
    cache_write_tokens BIGINT NULL COMMENT '写入供应商提示词缓存的Token',

    measure_type VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN'
        COMMENT '计量方式：PROVIDER、ESTIMATED、UNKNOWN',

    duration_ms BIGINT NULL COMMENT '本次模型调用耗时，单位毫秒',
    finish_reason VARCHAR(32) NULL COMMENT '模型结束原因',
    success TINYINT NOT NULL DEFAULT 1 COMMENT '是否调用成功：1成功，0失败',
    error_message VARCHAR(512) NULL COMMENT '错误信息摘要',

    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

    INDEX idx_model_usage_run_id (run_id),
    INDEX idx_model_usage_conversation_id (conversation_id),
    INDEX idx_model_usage_user_created (user_id, created_at),
    INDEX idx_model_usage_call_type_created (call_type, created_at),
    INDEX idx_model_usage_model_created (model_name, created_at)
) COMMENT='大模型调用Token使用明细表';


-- ai_run_trace 保存整次聊天的汇总数据，方便直接查询。
ALTER TABLE ai_run_trace
    ADD COLUMN prompt_tokens INT NOT NULL DEFAULT 0
    COMMENT '本次运行累计输入Token',
    ADD COLUMN completion_tokens INT NOT NULL DEFAULT 0
        COMMENT '本次运行累计输出Token',
    ADD COLUMN total_tokens INT NOT NULL DEFAULT 0
        COMMENT '本次运行累计总Token',
    ADD COLUMN model_call_count INT NOT NULL DEFAULT 0
        COMMENT '本次运行模型调用次数';

ALTER TABLE ai_field_dictionary
    ADD COLUMN required_output TINYINT NOT NULL DEFAULT 0
    COMMENT '是否为必答字段：1是，0否',
    ADD COLUMN visible TINYINT NOT NULL DEFAULT 1
        COMMENT '是否允许展示：1是，0否',
    ADD COLUMN display_order INT NOT NULL DEFAULT 0
        COMMENT '字段展示顺序，数值越小越靠前',
    ADD COLUMN display_group VARCHAR(64) NULL
        COMMENT '展示分组，例如基本信息、合同信息、进度信息',
    ADD COLUMN null_display_text VARCHAR(128) NULL
        COMMENT '字段为空时的展示文本';