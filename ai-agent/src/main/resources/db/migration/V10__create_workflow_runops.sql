-- ============================================================
-- Workflow RunOps运行记录。
--
-- 注意：
-- 1. 仅修改AI项目数据库；
-- 2. 不修改PM系统；
-- 3. 不保存Authorization、Cookie、Token；
-- 4. 支持CHAT、DEBUG、RETRY三种运行来源。
-- ============================================================


-- 为Graph节点轨迹增加确定性定位字段。
ALTER TABLE ai_run_step
    ADD COLUMN node_id VARCHAR(128) NULL
        COMMENT 'GraphSpec节点ID',
    ADD COLUMN execution_path VARCHAR(512) NULL
        COMMENT '节点执行路径，例如root/project_loop[2]';

CREATE INDEX idx_run_step_node
    ON ai_run_step (
                    run_id,
                    node_id
        );


CREATE TABLE ai_workflow_run (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '工作流运行记录ID',

    run_id VARCHAR(64) NOT NULL COMMENT '工作流运行ID',
    agent_run_id VARCHAR(64) NULL COMMENT '所属Agent聊天runId，DEBUG时为空',

    root_run_id VARCHAR(64) NOT NULL COMMENT '重试链路根运行ID',
    source_run_id VARCHAR(64) NULL COMMENT '本次重试来源运行ID',

    request_id VARCHAR(64) NULL COMMENT '失败重试幂等请求ID',

    workflow_id BIGINT NOT NULL COMMENT '工作流定义ID',
    workflow_code VARCHAR(128) NOT NULL COMMENT '工作流编码',
    workflow_name VARCHAR(128) NOT NULL COMMENT '运行时工作流名称',

    workflow_version_id BIGINT NULL COMMENT '发布版本ID，草稿DEBUG时为空',
    workflow_version_no INT NULL COMMENT '发布版本号，草稿DEBUG时为空',
    config_revision INT NOT NULL COMMENT '本次执行的配置修订号',
    config_checksum CHAR(64) NOT NULL COMMENT '本次执行配置校验和',

    origin VARCHAR(32) NOT NULL
     COMMENT '运行来源：CHAT/DEBUG/RETRY',

    status VARCHAR(32) NOT NULL
     COMMENT '状态：RUNNING/SUCCESS/PARTIAL_SUCCESS/FAILED',

    user_id VARCHAR(128) NOT NULL COMMENT '执行用户ID',

    input_json MEDIUMTEXT NOT NULL COMMENT '经过Schema清洗后的工作流输入',
    result_json MEDIUMTEXT NULL COMMENT '安全工作流执行结果',

    total_item_count INT NOT NULL DEFAULT 0 COMMENT 'FOREACH总项目数',
    success_item_count INT NOT NULL DEFAULT 0 COMMENT '成功项目数',
    failure_item_count INT NOT NULL DEFAULT 0 COMMENT '失败项目数',
    skipped_item_count INT NOT NULL DEFAULT 0 COMMENT '跳过项目数',

    error_code VARCHAR(128) NULL COMMENT '工作流错误码',
    error_message VARCHAR(1000) NULL COMMENT '安全错误信息',

    duration_ms BIGINT NULL COMMENT '执行耗时毫秒',

    started_at DATETIME NOT NULL COMMENT '开始时间',
    finished_at DATETIME NULL COMMENT '结束时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

    UNIQUE KEY uk_workflow_run_id (run_id),
    UNIQUE KEY uk_workflow_request_id (request_id),

    KEY idx_workflow_run_page (
    user_id,
    started_at
    ),

    KEY idx_workflow_run_status (
    workflow_code,
    status,
    started_at
    ),

    KEY idx_workflow_run_retry (
    root_run_id,
    source_run_id
    )
) COMMENT='AI工作流运行实例表';


CREATE TABLE ai_workflow_run_item (
id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '工作流单项运行记录ID',

run_id VARCHAR(64) NOT NULL COMMENT '工作流运行ID',
node_id VARCHAR(128) NOT NULL COMMENT 'FOREACH节点ID',
item_index INT NOT NULL COMMENT '项目原始顺序，从0开始',

item_json TEXT NULL COMMENT '当前项目输入，不包含Token',
result_json MEDIUMTEXT NULL COMMENT '当前项目安全执行结果',

status VARCHAR(32) NOT NULL
COMMENT '状态：SUCCESS/FAILED/SKIPPED',

error_code VARCHAR(128) NULL COMMENT '错误码',
error_message VARCHAR(1000) NULL COMMENT '安全错误信息',

duration_ms BIGINT NOT NULL DEFAULT 0 COMMENT '项目执行耗时',

created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

UNIQUE KEY uk_workflow_run_item (
run_id,
node_id,
item_index
),

KEY idx_workflow_failed_item (
        run_id,
        node_id,
        status
    )
) COMMENT='AI工作流FOREACH单项结果表';

CREATE INDEX idx_workflow_run_recovery
    ON ai_workflow_run (
                        status,
                        started_at
        );