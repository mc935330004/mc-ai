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