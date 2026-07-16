-- ============================================================
-- 扩展 AI 能力定义，使能力可以通过配置适配现有业务 API。
--
-- 注意：
-- 1. 不修改 PM 业务系统数据库；
-- 2. request_binding_json 暂时允许为空，方便保存未完成草稿；
-- 3. 正式发布时由 Java 服务强制要求 request_binding_json；
-- 4. 已经发布的旧能力不会在迁移时被自动停用。
-- ============================================================

ALTER TABLE ai_capability_definition
    ADD COLUMN request_binding_json TEXT NULL
        COMMENT '请求参数绑定配置JSON，描述PATH、QUERY、BODY参数来源和目标位置',
    ADD COLUMN response_binding_json TEXT NULL
        COMMENT '响应解释配置JSON，描述业务码、消息和有效数据路径',
    ADD COLUMN config_revision INT NOT NULL DEFAULT 1
        COMMENT '能力配置修订号，每次保存草稿时递增',
    ADD COLUMN config_checksum CHAR(64) NULL
        COMMENT '已发布能力配置的SHA-256校验和',
    ADD COLUMN validated_at DATETIME NULL
        COMMENT '最近一次通过完整发布校验的时间';

ALTER TABLE ai_capability_definition
    ADD COLUMN active_version_id BIGINT NULL
        COMMENT '当前运行时使用的能力发布版本ID',
    ADD COLUMN draft_dirty TINYINT NOT NULL DEFAULT 1
        COMMENT '草稿是否存在未发布修改：1是，0否';

CREATE TABLE ai_capability_version (
id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '能力版本主键',

capability_id BIGINT NOT NULL COMMENT '能力定义ID',
capability_code VARCHAR(128) NOT NULL COMMENT '能力稳定编码',

version_no INT NOT NULL COMMENT '能力版本号，从1递增',
config_revision INT NOT NULL COMMENT '发布时对应的草稿修订号',

snapshot_json MEDIUMTEXT NOT NULL COMMENT '完整不可变能力配置快照',
config_checksum CHAR(64) NOT NULL COMMENT '快照SHA-256校验和',

status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'
   COMMENT '版本状态：ACTIVE/RETIRED',

published_by VARCHAR(128) NOT NULL COMMENT '发布用户ID',
published_at DATETIME NOT NULL COMMENT '发布时间',
retired_at DATETIME NULL COMMENT '版本退役时间',
created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

UNIQUE KEY uk_capability_version_no (
capability_id,
version_no
),

KEY idx_capability_version_code (
capability_code,
version_no
),

KEY idx_capability_version_status (
capability_id,
status
),

KEY idx_capability_version_checksum (
        capability_id,
        config_checksum
    )
) COMMENT='AI能力不可变发布版本表';

CREATE INDEX idx_capability_active_version
    ON ai_capability_definition(active_version_id);