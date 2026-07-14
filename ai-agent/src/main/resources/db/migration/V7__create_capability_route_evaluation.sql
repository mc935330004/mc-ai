-- ============================================================
-- 1. 能力路由决策日志
-- 每次 DynamicCapabilityPlanner 运行保存一条
-- ============================================================
CREATE TABLE ai_capability_route_log (
     id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',

     run_id VARCHAR(64) NOT NULL COMMENT 'Agent运行ID',
     user_question TEXT NOT NULL COMMENT '用户原始问题',

     decision_status VARCHAR(32) NOT NULL
         COMMENT '决策状态：SELECTED/CLARIFY/NO_CANDIDATE/FAILED',

     selected_capability_code VARCHAR(128) DEFAULT NULL
         COMMENT '最终选择的能力编码',

     confidence DECIMAL(8, 6) DEFAULT NULL
         COMMENT '模型选择置信度',

     candidates_json JSON DEFAULT NULL
         COMMENT '候选能力、关键词分数、向量分数、融合分数',

     reason VARCHAR(1000) DEFAULT NULL
         COMMENT '选择、追问或失败原因',

     clarify_question VARCHAR(1000) DEFAULT NULL
         COMMENT '需要追问用户的问题',

     duration_ms BIGINT NOT NULL DEFAULT 0
         COMMENT '能力规划耗时',

     created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
         COMMENT '创建时间',

     UNIQUE KEY uk_capability_route_log_run_id (run_id),
     KEY idx_capability_route_log_selected_code (selected_capability_code),
     KEY idx_capability_route_log_status_created (decision_status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
COMMENT='AI能力路由决策日志';


-- ============================================================
-- 2. 人工反馈
-- 普通提交先进入 PENDING，审核后才进入评测集
-- ============================================================
CREATE TABLE ai_capability_route_feedback (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',

  route_log_id BIGINT NOT NULL COMMENT '路由日志ID',
  run_id VARCHAR(64) NOT NULL COMMENT 'Agent运行ID',

  original_capability_code VARCHAR(128) DEFAULT NULL
      COMMENT '系统原始选择能力',

  correct_flag TINYINT NOT NULL
      COMMENT '原始选择是否正确：1正确，0错误',

  expected_capability_code VARCHAR(128) DEFAULT NULL
      COMMENT '人工认为正确的能力编码',

  feedback_status VARCHAR(32) NOT NULL DEFAULT 'PENDING'
      COMMENT 'PENDING/APPROVED/REJECTED',

  comment VARCHAR(1000) DEFAULT NULL COMMENT '反馈说明',

  submitted_by VARCHAR(64) DEFAULT NULL COMMENT '提交人',
  reviewed_by VARCHAR(64) DEFAULT NULL COMMENT '审核人',
  reviewed_at DATETIME DEFAULT NULL COMMENT '审核时间',

  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

  KEY idx_route_feedback_run_id (run_id),
  KEY idx_route_feedback_status (feedback_status),
  KEY idx_route_feedback_confusion (
        original_capability_code,
        expected_capability_code
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
COMMENT='AI能力路由人工反馈';


-- ============================================================
-- 3. 路由回归测试样本
-- ============================================================
CREATE TABLE ai_capability_route_case (
  id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',

  user_question TEXT NOT NULL COMMENT '测试问题',

  expected_route_type VARCHAR(64) DEFAULT NULL
      COMMENT '期望路由类型',

  expected_capability_code VARCHAR(128) DEFAULT NULL
      COMMENT '期望能力编码',

  should_clarify TINYINT NOT NULL DEFAULT 0
      COMMENT '是否期望进入追问',

  expected_input_json JSON DEFAULT NULL
      COMMENT '期望提取的接口参数；为空表示不校验参数',

  source_type VARCHAR(32) NOT NULL DEFAULT 'MANUAL'
      COMMENT 'MANUAL/FEEDBACK',

  source_feedback_id BIGINT DEFAULT NULL
      COMMENT '从哪个反馈审核产生',

  tags VARCHAR(512) DEFAULT NULL
      COMMENT '标签，例如：项目,统计,同义表达',

  enabled TINYINT NOT NULL DEFAULT 1,

  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
      ON UPDATE CURRENT_TIMESTAMP,

  UNIQUE KEY uk_route_case_feedback (source_feedback_id),
  KEY idx_route_case_enabled (enabled),
  KEY idx_route_case_capability (expected_capability_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
COMMENT='AI能力路由回归测试样本';


-- ============================================================
-- 4. 一次评测任务
-- ============================================================
CREATE TABLE ai_capability_route_eval_run (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,

  eval_run_id VARCHAR(64) NOT NULL COMMENT '评测任务ID',

  status VARCHAR(32) NOT NULL
      COMMENT 'RUNNING/SUCCESS/FAILED',

  total_count INT NOT NULL DEFAULT 0,
  passed_count INT NOT NULL DEFAULT 0,
  failed_count INT NOT NULL DEFAULT 0,

  accuracy DECIMAL(8, 6) NOT NULL DEFAULT 0,

  error_message VARCHAR(1000) DEFAULT NULL,

  started_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  finished_at DATETIME DEFAULT NULL,

  UNIQUE KEY uk_route_eval_run_id (eval_run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
COMMENT='AI能力路由评测任务';


-- ============================================================
-- 5. 每条样本的评测明细
-- ============================================================
CREATE TABLE ai_capability_route_eval_detail (
     id BIGINT PRIMARY KEY AUTO_INCREMENT,

     eval_run_id VARCHAR(64) NOT NULL,
     case_id BIGINT NOT NULL,

     user_question TEXT NOT NULL,

     expected_route_type VARCHAR(64) DEFAULT NULL,
     actual_route_type VARCHAR(64) DEFAULT NULL,

     expected_capability_code VARCHAR(128) DEFAULT NULL,
     actual_capability_code VARCHAR(128) DEFAULT NULL,

     expected_clarify TINYINT NOT NULL DEFAULT 0,
     actual_clarify TINYINT NOT NULL DEFAULT 0,

     expected_input_json JSON DEFAULT NULL,
     actual_input_json JSON DEFAULT NULL,

     passed TINYINT NOT NULL DEFAULT 0,
     failure_reason VARCHAR(1000) DEFAULT NULL,

     duration_ms BIGINT NOT NULL DEFAULT 0,

     created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,

     KEY idx_route_eval_detail_run (eval_run_id),
     KEY idx_route_eval_detail_case (case_id),
     KEY idx_route_eval_detail_passed (passed)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
COMMENT='AI能力路由评测明细';