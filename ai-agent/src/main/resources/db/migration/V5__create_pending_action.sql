CREATE TABLE ai_pending_action (
id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
run_id VARCHAR(64) NOT NULL COMMENT 'Agent运行ID',
user_id VARCHAR(64) NOT NULL COMMENT '发起操作的用户ID',
capability_code VARCHAR(128) NOT NULL COMMENT '待执行能力编码',
capability_name VARCHAR(128) NOT NULL COMMENT '待执行能力名称',
input_json MEDIUMTEXT NOT NULL COMMENT '确认后使用的固定操作参数',
action_summary VARCHAR(512) DEFAULT NULL COMMENT '面向用户展示的操作摘要',
status VARCHAR(32) NOT NULL DEFAULT 'PENDING'
   COMMENT 'PENDING/CONFIRMED/EXECUTING/SUCCESS/FAILED/CANCELLED/EXPIRED',
idempotency_key VARCHAR(64) NOT NULL COMMENT '写操作幂等键',
expire_at DATETIME NOT NULL COMMENT '操作确认过期时间',
confirmed_at DATETIME DEFAULT NULL COMMENT '用户确认时间',
executed_at DATETIME DEFAULT NULL COMMENT '实际执行时间',
error_message TEXT DEFAULT NULL COMMENT '执行失败原因',
created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
   ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
output_json MEDIUMTEXT DEFAULT NULL COMMENT '业务系统写操作执行结果JSON',

UNIQUE KEY uk_pending_action_run_id (run_id),
UNIQUE KEY uk_pending_action_idempotency_key (idempotency_key),
KEY idx_pending_action_user_status (user_id, status),
KEY idx_pending_action_expire_at (expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI待确认操作表';

CREATE TABLE ai_business_system (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    system_code VARCHAR(64) NOT NULL COMMENT '业务系统编码',
    system_name VARCHAR(128) NOT NULL COMMENT '业务系统名称',
    base_url VARCHAR(500) NOT NULL COMMENT '业务系统基础地址',
    openapi_url VARCHAR(500) DEFAULT NULL COMMENT 'OpenAPI文档地址',
    auth_type VARCHAR(32) NOT NULL DEFAULT 'FORWARD'
        COMMENT '认证方式：FORWARD/NONE',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用：1启用，0停用',
    remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    UNIQUE KEY uk_business_system_code (system_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI业务系统配置表';


ALTER TABLE ai_capability_definition
    ADD COLUMN system_code VARCHAR(64) DEFAULT NULL
    COMMENT '所属业务系统编码',
    ADD COLUMN source_type VARCHAR(32) NOT NULL DEFAULT 'MANUAL'
        COMMENT '配置来源：MANUAL/OPENAPI',
    ADD COLUMN source_operation_id VARCHAR(128) DEFAULT NULL
        COMMENT 'OpenAPI operationId',
    ADD COLUMN publish_status VARCHAR(32) NOT NULL DEFAULT 'PUBLISHED'
        COMMENT '发布状态：DRAFT/PUBLISHED/DISABLED';

CREATE INDEX idx_capability_system_code
    ON ai_capability_definition (system_code);

CREATE INDEX idx_capability_publish_status
    ON ai_capability_definition (publish_status);

ALTER TABLE ai_field_dictionary
    ADD COLUMN source_type VARCHAR(32) NOT NULL DEFAULT 'MANUAL'
    COMMENT '字段来源：MANUAL/OPENAPI/SAMPLE/AI',
    ADD COLUMN manual_override TINYINT NOT NULL DEFAULT 0
        COMMENT '是否经过人工确认：1是，0否',
    ADD COLUMN publish_status VARCHAR(32) NOT NULL DEFAULT 'PUBLISHED'
        COMMENT '发布状态：DRAFT/PUBLISHED/DISABLED',
    ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';

CREATE UNIQUE INDEX uk_capability_system_operation
    ON ai_capability_definition (system_code,source_operation_id);

