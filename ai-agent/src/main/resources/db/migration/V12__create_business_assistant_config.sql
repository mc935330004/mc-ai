-- ============================================================
-- 业务助手当前配置与字段策略。
--
-- 设计说明：
-- 1. 配置表只保存当前版本，不保存历史配置；
-- 2. 工作流、数据集和方案之间使用有索引的逻辑引用；
-- 3. JSON 仅保存配置、映射、布局和规则，不保存业务原始响应；
-- 4. 运行快照通过配置校验和记录实际使用的配置。
-- ============================================================

CREATE TABLE ai_report_dataset
(
    id                    BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '报告数据集ID',
    dataset_code          VARCHAR(128) NOT NULL COMMENT '数据集稳定编码',
    dataset_name          VARCHAR(128) NOT NULL COMMENT '数据集名称',
    domain_code           VARCHAR(64)  NOT NULL COMMENT '业务域编码',
    subject_types_json    JSON         NOT NULL COMMENT '支持的报告主体类型配置',
    query_workflow_code   VARCHAR(128) NOT NULL COMMENT '查询工作流编码',
    access_workflow_code  VARCHAR(128) NULL COMMENT '独立权限校验工作流编码',
    input_mapping_json    JSON         NOT NULL COMMENT '规范参数到工作流输入字段的显式映射',
    ttl_minutes           INT          NOT NULL COMMENT '数据集快照有效分钟数，范围1至1440',
    association_mode      VARCHAR(32)  NOT NULL COMMENT '项目关联方式',
    max_concurrency       INT          NOT NULL COMMENT '数据集最大并发数',
    enabled               TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用：0否，1是',
    config_checksum       CHAR(64)     NOT NULL COMMENT '当前数据集配置SHA-256',
    field_policy_checksum CHAR(64)     NOT NULL COMMENT '当前字段策略SHA-256',
    created_by            VARCHAR(128) NOT NULL COMMENT '创建人',
    updated_by            VARCHAR(128) NOT NULL COMMENT '最后修改人',
    created_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    UNIQUE KEY uk_dataset_code (dataset_code),
    KEY idx_dataset_enabled_domain (enabled, domain_code),
    KEY idx_dataset_query_workflow (query_workflow_code),
    KEY idx_dataset_access_workflow (access_workflow_code)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '业务助手报告数据集当前配置表';


CREATE TABLE ai_report_dataset_field
(
    id             BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '数据集字段策略ID',
    dataset_id     BIGINT       NOT NULL COMMENT '报告数据集ID，逻辑引用ai_report_dataset.id',
    field_id       BIGINT       NULL COMMENT '已发布字段字典ID，可为空表示仅使用标准事实',
    fact_code      VARCHAR(128) NOT NULL COMMENT '标准事实编码',
    fact_name      VARCHAR(128) NOT NULL COMMENT '标准事实名称',
    fact_type      VARCHAR(32)  NOT NULL COMMENT '标准事实类型',
    calculable     TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否允许确定性统计',
    displayable    TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否允许展示给用户',
    exportable     TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否允许写入报告文件',
    model_visible  TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否允许进入受控模型上下文',
    filterable     TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否允许作为确定性裁剪条件',
    mask_strategy  VARCHAR(32)  NOT NULL COMMENT '字段脱敏策略',
    grain          VARCHAR(64)  NOT NULL COMMENT '事实粒度',
    display_order  INT          NOT NULL DEFAULT 0 COMMENT '展示顺序',
    created_by     VARCHAR(128) NOT NULL COMMENT '创建人',
    updated_by     VARCHAR(128) NOT NULL COMMENT '最后修改人',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    UNIQUE KEY uk_dataset_fact (dataset_id, fact_code),
    KEY idx_dataset_field_order (dataset_id, display_order, id),
    KEY idx_dataset_field_dictionary (field_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '报告数据集标准事实与字段策略表';


CREATE TABLE ai_project_panorama_profile
(
    id              BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '项目全景方案ID',
    project_type    VARCHAR(64)  NOT NULL COMMENT '项目类型',
    profile_name    VARCHAR(128) NOT NULL COMMENT '方案名称',
    enabled         TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用：0否，1是',
    config_checksum CHAR(64)     NOT NULL COMMENT '当前方案配置SHA-256',
    created_by      VARCHAR(128) NOT NULL COMMENT '创建人',
    updated_by      VARCHAR(128) NOT NULL COMMENT '最后修改人',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    UNIQUE KEY uk_project_type (project_type),
    KEY idx_panorama_profile_enabled (enabled)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '项目类型对应的当前全景方案表';


CREATE TABLE ai_project_panorama_module
(
    id            BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '项目全景模块ID',
    profile_id    BIGINT       NOT NULL COMMENT '全景方案ID，逻辑引用ai_project_panorama_profile.id',
    dataset_code  VARCHAR(128) NOT NULL COMMENT '报告数据集编码，逻辑引用ai_report_dataset.dataset_code',
    required_flag TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否为必需模块',
    display_order INT          NOT NULL DEFAULT 0 COMMENT '模块展示顺序',
    timeout_ms    INT          NOT NULL COMMENT '模块执行超时毫秒数',
    created_by    VARCHAR(128) NOT NULL COMMENT '创建人',
    updated_by    VARCHAR(128) NOT NULL COMMENT '最后修改人',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    UNIQUE KEY uk_profile_dataset (profile_id, dataset_code),
    KEY idx_panorama_module_order (profile_id, display_order, id),
    KEY idx_panorama_module_dataset (dataset_code)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '项目全景方案数据集模块表';


CREATE TABLE ai_project_issue_rule
(
    id               BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '项目问题规则ID',
    profile_id       BIGINT        NOT NULL COMMENT '全景方案ID，逻辑引用ai_project_panorama_profile.id',
    rule_code        VARCHAR(128)  NOT NULL COMMENT '规则稳定编码',
    severity         VARCHAR(16)   NOT NULL COMMENT '问题严重等级',
    condition_json   JSON          NOT NULL COMMENT '确定性规则条件配置',
    message_template VARCHAR(1000) NOT NULL COMMENT '安全问题消息模板',
    enabled          TINYINT(1)    NOT NULL DEFAULT 1 COMMENT '是否启用：0否，1是',
    created_by       VARCHAR(128)  NOT NULL COMMENT '创建人',
    updated_by       VARCHAR(128)  NOT NULL COMMENT '最后修改人',
    created_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at       DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    UNIQUE KEY uk_profile_rule (profile_id, rule_code),
    KEY idx_project_issue_rule_match (profile_id, enabled, severity)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '项目单模块与跨模块确定性问题规则表';


CREATE TABLE ai_composite_report_template
(
    id              BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '组合报告模板ID',
    template_code   VARCHAR(128) NOT NULL COMMENT '模板稳定编码',
    subject_type    VARCHAR(32)  NULL COMMENT '适用报告主体类型，为空表示通用',
    project_type    VARCHAR(64)  NULL COMMENT '适用项目类型，为空表示不限定',
    title           VARCHAR(256) NOT NULL COMMENT '报告标题模板',
    layout_json     JSON         NOT NULL COMMENT '跨数据集章节顺序与格式布局',
    enabled         TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用：0否，1是',
    config_checksum CHAR(64)     NOT NULL COMMENT '当前模板配置SHA-256',
    created_by      VARCHAR(128) NOT NULL COMMENT '创建人',
    updated_by      VARCHAR(128) NOT NULL COMMENT '最后修改人',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    UNIQUE KEY uk_template_code (template_code),
    KEY idx_composite_template_match (subject_type, project_type, enabled)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '组合报告当前模板表';
