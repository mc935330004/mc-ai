-- ============================================================
-- 业务助手安全快照、异步报告任务及章节引用。
--
-- 设计约束：
-- 1. 快照只保存字段策略处理后的小型标准事实，大明细复用结果制品；
-- 2. 快照最长保留24小时，且不得晚于引用结果制品；
-- 3. 报告任务使用唯一请求键和数据库租约支持幂等领取与崩溃恢复；
-- 4. 运行表只记录安全错误摘要和校验和，不保存来源接口原始响应或认证信息。
-- ============================================================

CREATE TABLE ai_business_snapshot
(
    snapshot_id          VARCHAR(32)  NOT NULL COMMENT '业务快照ID',
    session_id           VARCHAR(64)  NOT NULL COMMENT '聊天会话ID',
    user_id              VARCHAR(128) NOT NULL COMMENT '当前业务用户ID',
    subject_type         VARCHAR(32)  NOT NULL COMMENT '主体类型',
    subject_id           VARCHAR(128) NOT NULL COMMENT '主体稳定ID',
    dataset_code         VARCHAR(128) NOT NULL COMMENT '报告数据集编码',
    query_json           JSON         NOT NULL COMMENT '规范化安全查询条件',
    query_hash           CHAR(64)     NOT NULL COMMENT '规范查询条件SHA-256',
    status               VARCHAR(32)  NOT NULL COMMENT '状态：WRITING/COMPLETE/PARTIAL_SUCCESS/FAILED/EXPIRED',
    data_complete        TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '数据是否足以支持完整结论',
    facts_json           JSON         NOT NULL COMMENT '字段策略处理后的小型安全事实',
    config_checksum      CHAR(64)     NOT NULL COMMENT '实际数据集配置校验和',
    field_policy_checksum CHAR(64)    NOT NULL COMMENT '实际字段策略校验和',
    source_snapshot_id   VARCHAR(32)  NULL COMMENT '确定性派生来源快照ID',
    expires_at           DATETIME(3)  NOT NULL COMMENT '失效时间，最长24小时',
    created_at           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    completed_at         DATETIME(3)  NOT NULL COMMENT '安全事实完成时间',

    PRIMARY KEY (snapshot_id),
    KEY idx_snapshot_owner_subject
        (user_id, session_id, subject_type, subject_id, dataset_code, expires_at),
    KEY idx_snapshot_query_hash
        (user_id, session_id, query_hash, expires_at),
    KEY idx_snapshot_source (source_snapshot_id),
    KEY idx_snapshot_expiry (expires_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '业务助手安全业务快照表';


CREATE TABLE ai_business_snapshot_item
(
    id                       BIGINT       NOT NULL AUTO_INCREMENT COMMENT '快照执行项ID',
    snapshot_id              VARCHAR(32)  NOT NULL COMMENT '所属业务快照ID',
    item_key                 VARCHAR(128) NOT NULL COMMENT '模块、员工或批次稳定键',
    workflow_code            VARCHAR(128) NULL COMMENT '实际执行工作流编码',
    workflow_version_id      BIGINT       NULL COMMENT '实际发布版本ID',
    workflow_version_no      INT          NULL COMMENT '实际发布版本号',
    workflow_config_checksum CHAR(64)     NULL COMMENT '实际工作流配置校验和',
    workflow_run_id          VARCHAR(64)  NULL COMMENT '工作流运行ID',
    result_artifact_id       VARCHAR(32)  NULL COMMENT '安全结果制品ID',
    status                   VARCHAR(32)  NOT NULL COMMENT '状态：PENDING/AUTHORIZING/RUNNING/SUCCESS/NO_DATA/FAILED/TIMEOUT/RESTRICTED/SKIPPED',
    association_type         VARCHAR(32)  NULL COMMENT '直接或期间关联类型',
    total_count              INT          NOT NULL DEFAULT 0 COMMENT '安全口径总数',
    success_count            INT          NOT NULL DEFAULT 0 COMMENT '成功数量',
    failure_count            INT          NOT NULL DEFAULT 0 COMMENT '失败数量',
    safe_error_code          VARCHAR(128) NULL COMMENT '安全错误码',
    safe_error_message       VARCHAR(1000) NULL COMMENT '安全错误摘要',
    created_at               DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',

    PRIMARY KEY (id),
    UNIQUE KEY uk_snapshot_item (snapshot_id, item_key),
    KEY idx_snapshot_item_status (snapshot_id, status),
    KEY idx_snapshot_item_run (workflow_run_id),
    KEY idx_snapshot_item_artifact (result_artifact_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '业务快照模块员工或批次执行项表';


CREATE TABLE ai_composite_report_task
(
    task_id               VARCHAR(32)   NOT NULL COMMENT '组合报告任务ID',
    request_key           VARCHAR(128)  NOT NULL COMMENT '报告创建幂等请求键',
    request_fingerprint   CHAR(64)      NOT NULL COMMENT '规范请求内容SHA-256',
    session_id            VARCHAR(64)   NOT NULL COMMENT '聊天会话ID',
    user_id               VARCHAR(128)  NOT NULL COMMENT '当前业务用户ID',
    subject_type          VARCHAR(32)   NOT NULL COMMENT '报告主体类型',
    subject_id            VARCHAR(128)  NOT NULL COMMENT '报告主体稳定ID',
    template_code         VARCHAR(128)  NOT NULL COMMENT '组合报告模板编码',
    template_checksum     CHAR(64)      NOT NULL COMMENT '实际模板配置校验和',
    format                VARCHAR(16)   NOT NULL COMMENT '格式：XLSX/DOCX/PDF',
    status                VARCHAR(32)   NOT NULL COMMENT '状态：PENDING/RETRY/RUNNING/COLLECTING/RENDERING/SUCCESS/PARTIAL_SUCCESS/FAILED/CANCELLED/EXPIRED',
    data_complete         TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '报告数据是否完整',
    worker_id             VARCHAR(128)  NULL COMMENT '当前租约执行者',
    lease_until           DATETIME(3)   NULL COMMENT '当前租约失效时间',
    attempt_count         INT           NOT NULL DEFAULT 0 COMMENT '已尝试次数',
    max_attempts          INT           NOT NULL DEFAULT 3 COMMENT '最大尝试次数',
    next_retry_at         DATETIME(3)   NULL COMMENT '下次允许重试时间',
    storage_path          VARCHAR(1000) NULL COMMENT '受控存储相对路径',
    file_name             VARCHAR(255)  NULL COMMENT '安全下载文件名',
    mime_type             VARCHAR(128)  NULL COMMENT '文件媒体类型',
    file_size             BIGINT        NULL COMMENT '文件字节数',
    checksum              CHAR(64)      NULL COMMENT '报告文件SHA-256',
    safe_error_code       VARCHAR(128)  NULL COMMENT '安全错误码',
    safe_error_message    VARCHAR(1000) NULL COMMENT '安全错误摘要',
    expires_at            DATETIME(3)   NOT NULL COMMENT '文件和任务失效时间',
    created_at            DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at            DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    completed_at          DATETIME(3)   NULL COMMENT '完成时间',

    PRIMARY KEY (task_id),
    UNIQUE KEY uk_report_request_key (request_key),
    KEY idx_report_task_owner (user_id, session_id, created_at),
    KEY idx_report_task_status (status, updated_at),
    KEY idx_report_task_expiry (expires_at),
    KEY idx_report_task_claim (status, next_retry_at, lease_until)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '组合报告异步任务表';


CREATE TABLE ai_composite_report_section
(
    id                    BIGINT        NOT NULL AUTO_INCREMENT COMMENT '报告章节ID',
    task_id               VARCHAR(32)   NOT NULL COMMENT '所属组合报告任务ID',
    dataset_code          VARCHAR(128)  NOT NULL COMMENT '章节数据集编码',
    snapshot_id           VARCHAR(32)   NULL COMMENT '章节引用的安全业务快照ID',
    field_policy_checksum CHAR(64)      NOT NULL COMMENT '章节实际字段策略校验和',
    status                VARCHAR(32)   NOT NULL COMMENT '章节状态',
    display_order         INT           NOT NULL DEFAULT 0 COMMENT '章节显示顺序',
    safe_message          VARCHAR(1000) NULL COMMENT '章节安全状态说明',
    created_at            DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at            DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',

    PRIMARY KEY (id),
    UNIQUE KEY uk_report_section_order (task_id, display_order),
    KEY idx_report_section_snapshot (snapshot_id),
    KEY idx_report_section_dataset (task_id, dataset_code)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '组合报告章节及安全快照引用表';
