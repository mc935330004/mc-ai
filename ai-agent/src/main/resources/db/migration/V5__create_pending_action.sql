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

