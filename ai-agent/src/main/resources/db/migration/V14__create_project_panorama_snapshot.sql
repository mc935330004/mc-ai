-- ============================================================
-- 项目全景聚合快照及其模块快照引用。
--
-- 设计约束：
-- 1. 聚合表只保存安全查询条件、问题结论和完整性状态；
-- 2. 业务事实仍保存在V13业务快照中，聚合表只通过ID引用；
-- 3. 聚合失效时间不得晚于任一被引用模块快照。
-- ============================================================

CREATE TABLE ai_project_panorama_snapshot
(
    panorama_snapshot_id VARCHAR(32)  NOT NULL COMMENT '项目全景聚合快照ID',
    session_id           VARCHAR(64)  NOT NULL COMMENT '聊天会话ID',
    user_id              VARCHAR(128) NOT NULL COMMENT '当前业务用户ID',
    project_id           VARCHAR(128) NOT NULL COMMENT '权限校验后的项目稳定ID',
    project_code         VARCHAR(128) NOT NULL COMMENT '权限校验后的项目编码',
    project_type         VARCHAR(128) NOT NULL COMMENT '权限校验后的项目类型',
    profile_id           BIGINT       NOT NULL COMMENT '实际采用的项目全景方案ID',
    profile_checksum     CHAR(64)     NOT NULL COMMENT '实际采用的方案配置校验和',
    query_json           JSON         NOT NULL COMMENT '规范化安全查询条件',
    query_hash           CHAR(64)     NOT NULL COMMENT '规范查询条件SHA-256',
    status               VARCHAR(32)  NOT NULL COMMENT '状态：COMPLETE/PARTIAL_SUCCESS/FAILED',
    required_complete    TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '必选模块是否完整',
    all_modules_complete TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '全部模块是否完整',
    issues_json          JSON         NOT NULL COMMENT '基于可计算事实得出的安全问题结论',
    expires_at           DATETIME(3)  NOT NULL COMMENT '失效时间，不晚于引用模块快照',
    created_at           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    completed_at         DATETIME(3)  NOT NULL COMMENT '全景执行完成时间',

    PRIMARY KEY (panorama_snapshot_id),
    KEY idx_panorama_owner (user_id, session_id, created_at),
    KEY idx_panorama_project (user_id, project_id, expires_at),
    KEY idx_panorama_expiry (expires_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '项目全景聚合快照表';


CREATE TABLE ai_project_panorama_snapshot_module
(
    id                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '模块引用ID',
    panorama_snapshot_id VARCHAR(32)  NOT NULL COMMENT '项目全景聚合快照ID',
    dataset_code         VARCHAR(128) NOT NULL COMMENT '报告数据集编码',
    snapshot_id          VARCHAR(32)  NULL COMMENT '成功模块对应的安全业务快照ID',
    required_flag        TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否为必选模块',
    display_order        INT          NOT NULL COMMENT '模块展示顺序',
    status               VARCHAR(32)  NOT NULL COMMENT '模块执行状态',
    created_at           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',

    PRIMARY KEY (id),
    UNIQUE KEY uk_panorama_module_order (panorama_snapshot_id, display_order),
    UNIQUE KEY uk_panorama_module_dataset (panorama_snapshot_id, dataset_code),
    KEY idx_panorama_module_snapshot (snapshot_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '项目全景模块快照引用表';
