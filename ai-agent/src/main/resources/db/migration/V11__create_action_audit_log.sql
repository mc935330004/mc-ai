CREATE TABLE ai_action_audit_log
(
    id              BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    run_id          VARCHAR(64)  NOT NULL COMMENT 'Agent运行ID',
    user_id         VARCHAR(64)  NOT NULL COMMENT '发起操作的真实业务用户',
    capability_code VARCHAR(128) NOT NULL COMMENT 'WRITE能力编码',
    capability_name VARCHAR(128) DEFAULT NULL COMMENT 'WRITE能力名称',
    event_type      VARCHAR(32)  NOT NULL COMMENT 'WRITE生命周期事件',
    event_detail    VARCHAR(500) DEFAULT NULL COMMENT '安全事件摘要，不保存完整请求和响应',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '事件发生时间',

    KEY idx_action_audit_run_time (run_id, created_at),
    KEY idx_action_audit_user_time (user_id, created_at),
    KEY idx_action_audit_capability_time (capability_code, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = 'WRITE操作追加式审计日志';

-- ============================================================
-- P5-2 系统内告警中心。
--
-- 设计说明：
-- 1. ai_alert_rule 保存可配置的告警规则；
-- 2. ai_alert_record 保存实际产生的告警；
-- 3. active_key 只在告警未解决时保留；
-- 4. MySQL 唯一索引允许存在多个 NULL，因此告警解决后，
--    相同故障再次发生时可以生成新的告警；
-- 5. 不保存 Token、Cookie、Authorization 和完整请求参数。
-- ============================================================

CREATE TABLE ai_alert_rule
(
    id               BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '告警规则ID',
    rule_code        VARCHAR(64)  NOT NULL COMMENT '规则编码',
    rule_name        VARCHAR(128) NOT NULL COMMENT '规则名称',

    source_type      VARCHAR(32)  NOT NULL COMMENT '告警来源类型：WORKFLOW_RUN',
    match_error_code VARCHAR(128) NULL COMMENT '匹配的错误码，为空表示匹配所有错误码',

    severity         VARCHAR(16)  NOT NULL COMMENT '告警等级：INFO/WARNING/ERROR/CRITICAL',
    priority         INT          NOT NULL DEFAULT 100 COMMENT '匹配优先级，数值越小优先级越高',
    enabled          TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用：0否，1是',

    description      VARCHAR(500) NULL COMMENT '规则说明',

    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    UNIQUE KEY uk_alert_rule_code (rule_code),
    KEY idx_alert_rule_match (
        source_type,
        enabled,
        match_error_code,
        priority
    )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = 'AI告警规则表';


CREATE TABLE ai_alert_record
(
    id                 BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '告警记录ID',
    alert_no           VARCHAR(64)  NOT NULL COMMENT '告警编号',

    rule_id            BIGINT       NOT NULL COMMENT '命中的告警规则ID',
    rule_code          VARCHAR(64)  NOT NULL COMMENT '告警规则编码快照',
    rule_name          VARCHAR(128) NOT NULL COMMENT '告警规则名称快照',

    severity           VARCHAR(16)  NOT NULL COMMENT '告警等级',
    source_type        VARCHAR(32)  NOT NULL COMMENT '告警来源类型',

    first_source_id    VARCHAR(64)  NULL COMMENT '首次触发的运行ID',
    last_source_id     VARCHAR(64)  NULL COMMENT '最近一次触发的运行ID',

    workflow_id        BIGINT       NULL COMMENT '工作流定义ID',
    workflow_code      VARCHAR(128) NULL COMMENT '工作流编码',
    workflow_name      VARCHAR(128) NULL COMMENT '工作流名称',

    error_code         VARCHAR(128) NULL COMMENT '安全错误码',
    error_message      VARCHAR(1000) NULL COMMENT '脱敏后的错误信息',

    -- dedup_key 永久保存，用于审计和定位。
    dedup_key          CHAR(64)     NOT NULL COMMENT '告警去重摘要',

    -- OPEN、ACKNOWLEDGED 状态时保存去重摘要；
    -- RESOLVED 状态时设置为NULL。
    active_key         CHAR(64)     NULL COMMENT '活动告警唯一键，解决后清空',

    status             VARCHAR(32)  NOT NULL COMMENT '状态：OPEN/ACKNOWLEDGED/RESOLVED',
    occurrence_count   INT          NOT NULL DEFAULT 1 COMMENT '累计发生次数',

    first_occurred_at  DATETIME     NOT NULL COMMENT '首次发生时间',
    last_occurred_at   DATETIME     NOT NULL COMMENT '最近发生时间',

    acknowledged_by    VARCHAR(128) NULL COMMENT '确认人',
    acknowledged_at    DATETIME     NULL COMMENT '确认时间',

    resolved_by        VARCHAR(128) NULL COMMENT '解决人',
    resolved_at        DATETIME     NULL COMMENT '解决时间',

    created_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    UNIQUE KEY uk_alert_no (alert_no),

    -- 保证多线程或多实例同时处理相同异常时，
    -- 最终只有一条活动告警。
    UNIQUE KEY uk_alert_active_key (active_key),

    KEY idx_alert_record_page (
        status,
        severity,
        last_occurred_at
    ),

    KEY idx_alert_record_workflow (
        workflow_code,
        last_occurred_at
    ),

    KEY idx_alert_record_rule (
        rule_code,
        status,
        last_occurred_at
    ),

    KEY idx_alert_record_source (
        last_source_id
    )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = 'AI告警记录表';


-- 通用工作流失败规则。
-- 当没有更精确的错误码规则时使用。
INSERT INTO ai_alert_rule
(
    rule_code,
    rule_name,
    source_type,
    match_error_code,
    severity,
    priority,
    enabled,
    description
)
VALUES
    (
        'WORKFLOW_RUN_FAILED',
        '工作流执行失败',
        'WORKFLOW_RUN',
        NULL,
        'ERROR',
        100,
        1,
        '捕获没有专用规则的工作流执行失败'
    );


-- 系统容量不足规则。
INSERT INTO ai_alert_rule
(
    rule_code,
    rule_name,
    source_type,
    match_error_code,
    severity,
    priority,
    enabled,
    description
)
VALUES
    (
        'WORKFLOW_RUNTIME_BUSY',
        '工作流运行容量不足',
        'WORKFLOW_RUN',
        'GRAPH_RUNTIME_BUSY',
        'WARNING',
        10,
        1,
        '工作流并发容量耗尽时产生告警'
    );


-- 工作流异常中断规则。
INSERT INTO ai_alert_rule
(
    rule_code,
    rule_name,
    source_type,
    match_error_code,
    severity,
    priority,
    enabled,
    description
)
VALUES
    (
        'WORKFLOW_RUN_INTERRUPTED',
        '工作流异常中断',
        'WORKFLOW_RUN',
        'WORKFLOW_RUN_INTERRUPTED',
        'CRITICAL',
        10,
        1,
        '应用重启或异常退出造成运行中断时产生告警'
    );

CREATE TABLE ai_chat_session (
 id VARCHAR(64) PRIMARY KEY COMMENT '会话ID',
 user_id VARCHAR(64) NOT NULL COMMENT '用户ID',
 title VARCHAR(128) NOT NULL COMMENT '会话标题',
 model_code VARCHAR(64) NULL COMMENT '当前会话选择的模型编码',
 last_message VARCHAR(512) NULL COMMENT '最后一条消息摘要',
 message_count INT NOT NULL DEFAULT 0 COMMENT '消息数量',
 deleted TINYINT NOT NULL DEFAULT 0 COMMENT '是否删除：0否，1是',
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
 INDEX idx_chat_session_user_updated (user_id, updated_at)
) COMMENT='AI聊天会话表';

CREATE TABLE ai_chat_message (
 id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '消息ID',
 session_id VARCHAR(64) NOT NULL COMMENT '会话ID',
 user_id VARCHAR(64) NOT NULL COMMENT '用户ID',
 role VARCHAR(16) NOT NULL COMMENT '消息角色：USER、ASSISTANT',
 content LONGTEXT NOT NULL COMMENT '消息内容',
 run_id VARCHAR(64) NULL COMMENT 'Agent运行ID',
 model_code VARCHAR(64) NULL COMMENT '模型编码',
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
 INDEX idx_chat_message_session_created (session_id, created_at),
 INDEX idx_chat_message_user_created (user_id, created_at)
) COMMENT='AI聊天消息表';

ALTER TABLE ai_chat_message
    ADD COLUMN message_type VARCHAR(32) NOT NULL DEFAULT 'TEXT'
    COMMENT '消息类型：TEXT、ACTION_FORM、ACTION_PREVIEW'
        AFTER content,
    ADD COLUMN payload_json LONGTEXT NULL
        COMMENT '结构化消息载荷JSON快照'
        AFTER message_type;

--  每个聊天会话只保存一份最新业务状态。
CREATE TABLE ai_conversation_state (
   session_id VARCHAR(64) PRIMARY KEY COMMENT '会话ID',
   user_id VARCHAR(64) NOT NULL COMMENT '用户ID',
   state_json LONGTEXT NOT NULL COMMENT '结构化业务状态JSON',
   version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
   created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
   updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
       ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
   INDEX idx_conversation_state_user (user_id)
) COMMENT='AI会话业务状态表';

-- ============================================================
-- P6-1：工作流安全结果快照
-- 说明：
-- 1. 只保存经过字段隐藏策略过滤后的数据；
-- 2. 不保存 Token、Authorization、Cookie；
-- 3. 一个 runId 对应一份结果快照；
-- 4. expires_at 用于后续清理过期数据。
-- ============================================================

CREATE TABLE ai_result_artifact
(
    id                       VARCHAR(32)  NOT NULL COMMENT '结果快照ID',
    run_id                   VARCHAR(64)  NOT NULL COMMENT '工作流运行ID',
    session_id               VARCHAR(64)  NOT NULL COMMENT '聊天会话ID',
    user_id                  VARCHAR(64)  NOT NULL COMMENT '业务用户ID',

    workflow_code            VARCHAR(128) NOT NULL COMMENT '工作流编码',
    workflow_name            VARCHAR(128) NULL COMMENT '工作流名称',
    workflow_version_id      BIGINT       NULL COMMENT '工作流版本ID',

    status                   VARCHAR(16)  NOT NULL COMMENT 'WRITING/COMPLETE',
    partial_success          TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否部分成功',
    data_complete            TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '工作流业务数据是否完整',

    top_level_total_count    BIGINT       NOT NULL DEFAULT 0 COMMENT '顶层业务对象总数',
    top_level_success_count  BIGINT       NOT NULL DEFAULT 0 COMMENT '顶层成功数',
    top_level_failure_count  BIGINT       NOT NULL DEFAULT 0 COMMENT '顶层失败数',
    top_level_skipped_count  BIGINT       NOT NULL DEFAULT 0 COMMENT '顶层跳过数',

    descendant_total_count   BIGINT       NOT NULL DEFAULT 0 COMMENT '明细总数',
    descendant_success_count BIGINT       NOT NULL DEFAULT 0 COMMENT '明细成功数',
    descendant_failure_count BIGINT       NOT NULL DEFAULT 0 COMMENT '明细失败数',
    descendant_skipped_count BIGINT       NOT NULL DEFAULT 0 COMMENT '明细跳过数',

    planned_chunk_count      INT          NOT NULL DEFAULT 0 COMMENT '计划分块数',
    stored_chunk_count       INT          NOT NULL DEFAULT 0 COMMENT '实际保存分块数',
    source_char_count        INT          NOT NULL DEFAULT 0 COMMENT '安全结果原始字符数',
    chunk_char_count         INT          NOT NULL DEFAULT 0 COMMENT '分块JSON字符总数',

    payload_checksum         CHAR(64)     NOT NULL COMMENT '全部分块有序摘要',
    field_semantics_json     LONGTEXT     NULL COMMENT '字段中文语义快照',

    expires_at               DATETIME     NOT NULL COMMENT '过期时间',
    created_at               DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    completed_at             DATETIME     NULL COMMENT '快照写入完成时间',

    PRIMARY KEY (id),
    UNIQUE KEY uk_result_artifact_run (run_id),
    KEY idx_result_artifact_session (
        user_id,
        session_id,
        created_at
    ),
    KEY idx_result_artifact_expire (
        expires_at
    )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '工作流安全结果快照';


CREATE TABLE ai_result_artifact_chunk
(
    id              BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    artifact_id     VARCHAR(32) NOT NULL COMMENT '结果快照ID',
    chunk_no        INT         NOT NULL COMMENT '分块序号，从1开始',

    source_pointer  VARCHAR(512) NOT NULL COMMENT '数据在安全结果中的JSON Pointer',
    start_index     INT          NULL COMMENT '数组起始下标',
    end_index       INT          NULL COMMENT '数组结束下标',

    payload_json    LONGTEXT     NOT NULL COMMENT '经过字段过滤后的合法JSON分块',
    payload_sha256  CHAR(64)     NOT NULL COMMENT '当前分块SHA-256',
    char_count      INT          NOT NULL COMMENT '当前分块字符数',

    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

    PRIMARY KEY (id),
    UNIQUE KEY uk_result_artifact_chunk (
        artifact_id,
        chunk_no
        ),
    KEY idx_result_chunk_artifact (
        artifact_id
    )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '工作流安全结果快照分块';

CREATE TABLE ai_model_config
(
    id                          BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    model_code                  VARCHAR(64)  NOT NULL COMMENT '模型唯一编码',
    display_name                VARCHAR(128) NOT NULL COMMENT '后台和聊天页面展示名称',
    provider_code               VARCHAR(64)  NOT NULL COMMENT '供应商编码',
    api_type                    VARCHAR(32)  NOT NULL DEFAULT 'OPENAI_COMPATIBLE'
        COMMENT '接口类型，当前支持OPENAI_COMPATIBLE',
    base_url                    VARCHAR(512) NOT NULL COMMENT '模型API基础地址',
    api_key_ciphertext          TEXT         NOT NULL COMMENT 'AES-GCM加密后的API Key',
    model_name                  VARCHAR(128) NOT NULL COMMENT '供应商实际模型名称',

    temperature                 DECIMAL(4, 3) NOT NULL DEFAULT 0.200 COMMENT '默认温度',
    max_tokens                  INT           NOT NULL DEFAULT 2048 COMMENT '默认最大输出Token',
    timeout_seconds             INT           NOT NULL DEFAULT 30 COMMENT '请求超时秒数',

    streaming_supported         TINYINT NOT NULL DEFAULT 1 COMMENT '是否支持流式输出',
    structured_output_supported TINYINT NOT NULL DEFAULT 0 COMMENT '是否支持结构化输出',
    tool_calling_supported      TINYINT NOT NULL DEFAULT 0 COMMENT '是否支持工具调用',
    context_window              INT     NOT NULL DEFAULT 8192 COMMENT '上下文窗口大小',

    default_model               TINYINT NOT NULL DEFAULT 0 COMMENT '是否为系统默认模型',
    enabled                     TINYINT NOT NULL DEFAULT 0 COMMENT '是否允许业务请求使用',
    sort_order                  INT     NOT NULL DEFAULT 0 COMMENT '展示顺序',
    remark                      VARCHAR(500) NULL COMMENT '备注',

    last_test_success           TINYINT NULL COMMENT '最近一次测试是否成功',
    last_test_message           VARCHAR(255) NULL COMMENT '最近一次测试结果',
    last_test_duration_ms       BIGINT NULL COMMENT '最近一次测试耗时',
    last_test_at                DATETIME NULL COMMENT '最近一次测试时间',

    created_by                  VARCHAR(64) NOT NULL COMMENT '创建人',
    updated_by                  VARCHAR(64) NOT NULL COMMENT '修改人',
    created_at                  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at                  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',

    UNIQUE KEY uk_model_config_code (model_code),
    INDEX idx_model_config_enabled_sort (enabled, sort_order),
    INDEX idx_model_config_default (default_model)
) COMMENT = '大模型运行配置';

CREATE TABLE ai_model_assignment
(
    id                BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    subject_type      VARCHAR(16) NOT NULL COMMENT '配置对象类型：SYSTEM、USER',
    subject_id        VARCHAR(64) NOT NULL COMMENT '配置对象ID，系统配置固定为SYSTEM',
    model_code        VARCHAR(64) NOT NULL COMMENT '模型编码',
    default_model     TINYINT NOT NULL DEFAULT 0 COMMENT '是否为当前范围默认模型',
    fallback_priority INT NOT NULL DEFAULT 1 COMMENT '模型使用和故障转移优先级',
    created_by        VARCHAR(64) NOT NULL COMMENT '创建人',
    updated_by        VARCHAR(64) NOT NULL COMMENT '修改人',
    created_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',

    UNIQUE KEY uk_model_assignment_scope_model
        (subject_type, subject_id, model_code),

    UNIQUE KEY uk_model_assignment_scope_priority
        (subject_type, subject_id, fallback_priority),

    INDEX idx_model_assignment_subject
        (subject_type, subject_id)
) COMMENT = '系统和人员模型授权配置';

ALTER TABLE ai_model_usage
    ADD COLUMN model_code VARCHAR(64) NULL
        COMMENT '稳定模型配置编码'
        AFTER call_sequence,
    ADD COLUMN attempt_sequence INT NOT NULL DEFAULT 1
        COMMENT '同一次模型调用中的实际尝试序号'
        AFTER model_code,
    ADD COLUMN error_category VARCHAR(64) NULL
        COMMENT '模型调用失败分类'
        AFTER success;

CREATE INDEX idx_model_usage_model_time
    ON ai_model_usage (model_code, created_at);

-- ============================================================
-- 模型配置乐观锁与变更审计。
--
-- 设计说明：
-- 1. version 防止多个管理员并发覆盖模型配置；
-- 2. 审计表只记录操作身份和安全摘要；
-- 3. 禁止保存 API Key 原文、密文和完整配置。
-- ============================================================

ALTER TABLE ai_model_config
    ADD COLUMN version INT NOT NULL DEFAULT 0
    COMMENT '乐观锁版本号'
        AFTER last_test_at;

CREATE TABLE ai_model_config_audit_log
(
    id           BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '审计ID',
    operator_id  VARCHAR(64)  NOT NULL COMMENT '操作人业务用户ID',
    action_type  VARCHAR(32)  NOT NULL COMMENT '操作类型',
    target_type  VARCHAR(32)  NOT NULL COMMENT '对象类型',
    target_key   VARCHAR(128) NOT NULL COMMENT '模型编码或授权对象编码',
    event_detail VARCHAR(500) NULL COMMENT '服务端生成的安全摘要',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',

    KEY idx_model_config_audit_time (
        created_at
    ),
    KEY idx_model_config_audit_operator_time (
        operator_id,
        created_at
    ),
    KEY idx_model_config_audit_target_time (
        target_type,
        target_key,
        created_at
    ),
    KEY idx_model_config_audit_action_time (
        action_type,
        created_at
    )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '模型配置和授权变更审计日志';

-- ============================================================
-- 能力与工作流人员访问控制。
--
-- 设计说明：
-- 1. PUBLIC 表示所有已登录人员可以运行；
-- 2. RESTRICTED 表示只有授权名单中的人员可以运行；
-- 3. 权限绑定定义ID，不绑定发布版本；
-- 4. 授权名单为空不能代替资源停用；
-- 5. 当前关闭Flyway时，需要手工执行本区块SQL。
-- ============================================================

ALTER TABLE ai_capability_definition
    ADD COLUMN access_scope VARCHAR(16) NOT NULL DEFAULT 'PUBLIC'
        COMMENT '运行访问范围：PUBLIC、RESTRICTED'
        AFTER enabled;

ALTER TABLE ai_workflow_definition
    ADD COLUMN access_scope VARCHAR(16) NOT NULL DEFAULT 'PUBLIC'
        COMMENT '运行访问范围：PUBLIC、RESTRICTED'
        AFTER enabled;

CREATE TABLE ai_agent_resource_user_grant
(
    id            BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    resource_type VARCHAR(16) NOT NULL COMMENT '资源类型：CAPABILITY、WORKFLOW',
    resource_id   BIGINT      NOT NULL COMMENT '能力或工作流定义ID',
    user_id       VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin
                              NOT NULL COMMENT 'PM系统用户编码，区分大小写',
    created_by    VARCHAR(64) NOT NULL COMMENT '配置操作人',
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

    UNIQUE KEY uk_resource_user_grant (
        resource_type,
        resource_id,
        user_id
    ),
    KEY idx_resource_user_grant_user (
        user_id,
        resource_type
    ),
    KEY idx_resource_user_grant_resource (
        resource_type,
        resource_id
    )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '能力与工作流人员运行授权';
-- ============================================================
-- 知识库文档租户与部门访问控制
--
-- 说明：
-- 1. PUBLIC表示当前租户内公开，不表示全系统公开；
-- 2. DEPARTMENT表示当前租户内仅归属部门可检索；
-- 3. owner_dept继续作为展示名称，不参与权限判断；
-- 4. tenant_id和owner_dept_id必须来自服务端登录上下文。
-- ============================================================

ALTER TABLE knowledge_document
    ADD COLUMN tenant_id BIGINT NULL
        COMMENT 'PM租户ID，由服务端登录身份写入'
        AFTER id,
    ADD COLUMN access_scope VARCHAR(16) NOT NULL DEFAULT 'PUBLIC'
        COMMENT '文档访问范围：PUBLIC、DEPARTMENT'
        AFTER owner_dept,
    ADD COLUMN owner_dept_id BIGINT NULL
        COMMENT 'PM归属部门ID，仅DEPARTMENT范围参与权限判断'
        AFTER owner_dept;

CREATE INDEX idx_knowledge_document_tenant_scope
    ON knowledge_document (
                           tenant_id,
                           access_scope,
                           owner_dept_id,
                           status,
                           del_flag
        );

-- ============================================================
-- 知识库问答日志租户隔离
--
-- 说明：
-- 1. tenant_id用于管理端日志、详情、统计和引用记录隔离；
-- 2. user_id记录实际提问人；
-- 3. 历史日志无法可靠推断租户和人员，不自动填写虚假值；
-- 4. 新产生的日志必须由服务端认证上下文写入。
-- ============================================================

ALTER TABLE knowledge_query_log
    ADD COLUMN tenant_id BIGINT NULL
        COMMENT 'PM租户ID'
        AFTER id,
    ADD COLUMN user_id VARCHAR(64) NULL
        COMMENT 'PM提问人员编码'
        AFTER tenant_id;

CREATE INDEX idx_knowledge_query_log_tenant_time
    ON knowledge_query_log (
                            tenant_id,
                            created_at
        );

CREATE INDEX idx_knowledge_query_log_tenant_user_time
    ON knowledge_query_log (
                            tenant_id,
                            user_id,
                            created_at
        );