CREATE TABLE IF NOT EXISTS knowledge_category (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    parent_id BIGINT NOT NULL DEFAULT 0 COMMENT '父级分类ID，0表示根分类',
    name VARCHAR(100) NOT NULL COMMENT '分类名称，例如HR制度、财务流程、采购规范',
    code VARCHAR(100) COMMENT '分类编码，便于前端和权限系统识别',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '排序值，越小越靠前',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用：0-禁用，1-启用',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    del_flag TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除'
) COMMENT = '知识分类表，适用于企业制度、流程、规章分类';

CREATE INDEX idx_knowledge_category_parent
    ON knowledge_category (parent_id);

CREATE INDEX idx_knowledge_category_code
    ON knowledge_category (code);


CREATE TABLE IF NOT EXISTS knowledge_document (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    category_id BIGINT COMMENT '所属分类ID',
    title VARCHAR(255) NOT NULL COMMENT '文档标题，例如差旅报销制度',
    document_code VARCHAR(100) COMMENT '制度编号或文档编号',
    owner_dept VARCHAR(100) COMMENT '归属部门，例如人力资源部、财务部',
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT' COMMENT '状态：DRAFT-草稿，PUBLISHED-已发布，DEPRECATED-已废止，ARCHIVED-已归档',
    current_version_id BIGINT COMMENT '当前生效版本ID',
    summary VARCHAR(500) COMMENT '文档摘要',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    del_flag TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除'
) COMMENT = '制度知识文档主表';

CREATE INDEX idx_knowledge_document_category
    ON knowledge_document (category_id);

CREATE INDEX idx_knowledge_document_status
    ON knowledge_document (status);

CREATE INDEX idx_knowledge_document_code
    ON knowledge_document (document_code);


CREATE TABLE IF NOT EXISTS knowledge_document_version (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    document_id BIGINT NOT NULL COMMENT '文档ID',
    version_no VARCHAR(50) NOT NULL COMMENT '版本号，例如v1.0、v1.1',
    original_filename VARCHAR(255) NOT NULL COMMENT '原始文件名',
    content_type VARCHAR(100) COMMENT '文件类型',
    file_size BIGINT NOT NULL DEFAULT 0 COMMENT '文件大小，单位字节',
    file_hash VARCHAR(128) NOT NULL COMMENT '文件哈希，用于去重',
    storage_path VARCHAR(500) NOT NULL COMMENT '文件存储路径',
    parse_status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '解析状态：PENDING、PROCESSING、COMPLETED、FAILED',
    vector_status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '向量化状态：PENDING、PROCESSING、COMPLETED、FAILED',
    vector_error VARCHAR(500) COMMENT '向量化失败原因',
    chunk_count INT NOT NULL DEFAULT 0 COMMENT '切片数量',
    effective_start_time DATETIME COMMENT '生效开始时间',
    effective_end_time DATETIME COMMENT '生效结束时间',
    parse_engine varchar(32) DEFAULT NULL COMMENT '解析引擎：DOCLING、TIKA',
    parse_error varchar(500) DEFAULT NULL COMMENT '解析失败原因',
    published_at DATETIME COMMENT '发布时间',
    deprecated_at DATETIME COMMENT '废止时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    del_flag TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除'
) COMMENT = '制度知识文档版本表';

CREATE INDEX idx_doc_version_document
    ON knowledge_document_version (document_id);

CREATE INDEX idx_doc_version_file_hash
    ON knowledge_document_version (file_hash);

CREATE INDEX idx_doc_version_vector_status
    ON knowledge_document_version (vector_status);

CREATE INDEX idx_doc_version_effective_time
    ON knowledge_document_version (effective_start_time, effective_end_time);


CREATE TABLE IF NOT EXISTS knowledge_chunk (
    id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    document_id BIGINT NOT NULL COMMENT '文档ID',
    version_id BIGINT NOT NULL COMMENT '文档版本ID',
    chunk_index INT NOT NULL COMMENT '切片序号，从0开始',
    content TEXT NOT NULL COMMENT '切片文本内容',
    content_hash VARCHAR(128) COMMENT '切片内容哈希',
    token_count INT COMMENT '切片Token数量',
    page_number INT COMMENT '页码，无法识别时为空',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否参与检索：0-否，1-是',
    vector_id VARCHAR(100) COMMENT '向量库中的向量ID或业务标识',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    del_flag TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除'
) COMMENT = '知识切片明细表，用于审计、溯源和检索调试';

CREATE INDEX idx_knowledge_chunk_document
    ON knowledge_chunk (document_id);

CREATE INDEX idx_knowledge_chunk_version
    ON knowledge_chunk (version_id);

CREATE INDEX idx_knowledge_chunk_enabled
    ON knowledge_chunk (enabled);

CREATE UNIQUE INDEX uk_knowledge_chunk_version_index
    ON knowledge_chunk (version_id, chunk_index);

-- 文件：src/main/resources/db/migration/V5__create_knowledge_query_log.sql

CREATE TABLE IF NOT EXISTS knowledge_query_log (
   id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
   question TEXT NOT NULL COMMENT '用户问题',
   answer MEDIUMTEXT COMMENT '模型回答',
   top_k INT NOT NULL DEFAULT 5 COMMENT '召回数量',
   min_score DECIMAL(6, 4) COMMENT '最低相似度',
    status VARCHAR(32) NOT NULL COMMENT '状态：SUCCESS、NO_RESULT、FAILED',
    error_message VARCHAR(500) COMMENT '失败原因',
    duration_ms BIGINT COMMENT '耗时毫秒',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
    ) COMMENT = '企业知识问答日志表';

CREATE TABLE IF NOT EXISTS knowledge_query_reference (
     id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
     query_log_id BIGINT NOT NULL COMMENT '问答日志ID',
     document_id BIGINT COMMENT '文档ID',
     version_id BIGINT COMMENT '版本ID',
     chunk_id BIGINT COMMENT '切片ID',
     chunk_index INT COMMENT '切片序号',
     source VARCHAR(255) COMMENT '来源文件名',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'
    ) COMMENT = '企业知识问答引用表';

CREATE INDEX idx_query_reference_log
    ON knowledge_query_reference (query_log_id);

CREATE INDEX idx_query_log_created
    ON knowledge_query_log (created_at);