CREATE TABLE IF NOT EXISTS knowledge_base (
                                              id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',

                                              name VARCHAR(255) NOT NULL COMMENT '知识库名称',

    category VARCHAR(100) COMMENT '知识库分类，例如：项目文档、合同资料、制度文件等',

    original_filename VARCHAR(255) NOT NULL COMMENT '原始文件名，用户上传时的文件名称',

    content_type VARCHAR(100) COMMENT '文件类型，例如：application/pdf、text/plain、application/vnd.openxmlformats-officedocument.wordprocessingml.document',

    file_size BIGINT NOT NULL DEFAULT 0 COMMENT '文件大小，单位：字节',

    file_hash VARCHAR(128) NOT NULL COMMENT '文件哈希值，用于判断文件是否重复上传，例如 MD5、SHA256',

    storage_path VARCHAR(500) NOT NULL COMMENT '文件存储路径，可以是本地路径、OSS路径或MinIO路径',

    vector_status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '向量化状态：PENDING-待处理，PROCESSING-处理中，COMPLETED-成功，FAILED-失败',

    vector_error VARCHAR(500) COMMENT '向量化失败原因，用于记录解析、切片、Embedding或入库异常信息',

    chunk_count INT NOT NULL DEFAULT 0 COMMENT '文档切片数量，即该文件被拆分成多少个文本块',

    question_count BIGINT NOT NULL DEFAULT 0 COMMENT '该知识库被提问的次数',

    access_count BIGINT NOT NULL DEFAULT 0 COMMENT '该知识库被访问或检索的次数',

    last_accessed_at DATETIME COMMENT '最后一次访问时间',

    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '更新时间',

    del_flag TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除标识：0-未删除，1-已删除'
    ) COMMENT = '知识库文件主表，用于记录上传文件、向量化状态、访问统计等信息';


CREATE INDEX idx_kb_file_hash
    ON knowledge_base (file_hash);

CREATE INDEX idx_kb_vector_status
    ON knowledge_base (vector_status);

CREATE INDEX idx_kb_category
    ON knowledge_base (category);

CREATE INDEX idx_kb_created_at
    ON knowledge_base (created_at);