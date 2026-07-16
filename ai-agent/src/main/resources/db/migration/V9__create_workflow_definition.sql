-- ============================================================
-- AI 工作流定义与不可变发布版本。
--
-- 设计原则：
-- 1. ai_workflow_definition 保存当前可编辑草稿；
-- 2. ai_workflow_version 保存不可变发布快照；
-- 3. Agent 正式运行只能读取 active_version_id 指向的版本；
-- 4. 编辑草稿不能影响当前已经发布的运行版本；
-- 5. 不修改 PM 业务系统及其数据库。
-- ============================================================

CREATE TABLE ai_workflow_definition (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '工作流定义ID',

    workflow_code VARCHAR(128) NOT NULL COMMENT '工作流稳定编码，创建后禁止修改',
    workflow_name VARCHAR(128) NOT NULL COMMENT '工作流名称',
    description VARCHAR(512) NULL COMMENT '工作流业务说明',

    graph_spec_json MEDIUMTEXT NOT NULL COMMENT '当前可编辑GraphSpec草稿',

    config_revision INT NOT NULL DEFAULT 1
    COMMENT '草稿修订号，每次保存递增',

    draft_checksum CHAR(64) NOT NULL
    COMMENT '当前草稿GraphSpec的SHA-256',

    config_checksum CHAR(64) NULL
    COMMENT '当前发布版本GraphSpec的SHA-256',

    active_version_id BIGINT NULL
    COMMENT '当前运行时使用的发布版本ID',

    publish_status VARCHAR(32) NOT NULL DEFAULT 'DRAFT'
    COMMENT '发布状态：DRAFT/PUBLISHED/DISABLED',

    enabled TINYINT NOT NULL DEFAULT 0
    COMMENT '是否允许Agent调用：1允许，0禁止',

    draft_dirty TINYINT NOT NULL DEFAULT 1
    COMMENT '草稿是否存在未发布修改：1是，0否',

    validated_at DATETIME NULL
    COMMENT '当前草稿最近一次成功发布校验时间',

    created_by VARCHAR(128) NOT NULL COMMENT '创建用户ID',
    updated_by VARCHAR(128) NOT NULL COMMENT '最后修改用户ID',

    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
    ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    UNIQUE KEY uk_workflow_definition_code (workflow_code),

    KEY idx_workflow_definition_status (
    publish_status,
    enabled
    ),

    KEY idx_workflow_active_version (
    active_version_id
    )
) COMMENT='AI工作流定义表';


CREATE TABLE ai_workflow_version (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '工作流版本ID',

    workflow_id BIGINT NOT NULL COMMENT '工作流定义ID',
    workflow_code VARCHAR(128) NOT NULL COMMENT '工作流稳定编码',

    version_no INT NOT NULL COMMENT '工作流版本号，从1递增',
    config_revision INT NOT NULL COMMENT '发布时对应的草稿修订号',

    snapshot_json MEDIUMTEXT NOT NULL
    COMMENT '不可变GraphSpec发布快照',

    config_checksum CHAR(64) NOT NULL
    COMMENT '发布快照SHA-256',

    node_count INT NOT NULL DEFAULT 0
    COMMENT '节点总数，包含FOREACH子图节点',

    edge_count INT NOT NULL DEFAULT 0
    COMMENT '连线总数，包含FOREACH子图连线',

    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'
    COMMENT '版本状态：ACTIVE/RETIRED',

    published_by VARCHAR(128) NOT NULL COMMENT '发布用户ID',
    published_at DATETIME NOT NULL COMMENT '发布时间',

    retired_at DATETIME NULL COMMENT '版本退役时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

    UNIQUE KEY uk_workflow_version_no (
    workflow_id,
    version_no
    ),

    KEY idx_workflow_version_code (
    workflow_code,
    version_no
    ),

    KEY idx_workflow_version_status (
    workflow_id,
    status
    ),

    KEY idx_workflow_version_checksum (
    workflow_id,
    config_checksum
    )
) COMMENT='AI工作流不可变发布版本表';

ALTER TABLE ai_run_trace
    ADD COLUMN workflow_code VARCHAR(128) NULL
        COMMENT '本次运行实际执行的工作流编码',
    ADD COLUMN workflow_version_id BIGINT NULL
        COMMENT '本次运行实际执行的工作流版本ID';

CREATE INDEX idx_run_trace_workflow
    ON ai_run_trace (
                     workflow_code,
                     workflow_version_id
        );