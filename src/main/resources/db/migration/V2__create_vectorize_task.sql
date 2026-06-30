CREATE TABLE IF NOT EXISTS knowledge_base_vector_task (
                                                          id BIGINT PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',

                                                          knowledge_base_id BIGINT NOT NULL COMMENT '知识库ID，关联 knowledge_base 表的 id',

                                                          task_type VARCHAR(32) NOT NULL DEFAULT 'VECTORIZE' COMMENT '任务类型：VECTORIZE-向量化，RE_VECTORIZE-重新向量化，DELETE_VECTOR-删除向量',

    status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '任务状态：PENDING-待处理，PROCESSING-处理中，COMPLETED-成功，FAILED-失败',

    retry_count INT NOT NULL DEFAULT 0 COMMENT '当前重试次数',

    max_retry_count INT NOT NULL DEFAULT 3 COMMENT '最大重试次数，超过后任务不再自动重试',

    lock_owner VARCHAR(100) COMMENT '任务锁持有者，用于标识当前由哪个服务实例处理该任务',

    locked_at DATETIME COMMENT '任务加锁时间，用于判断任务是否长时间未释放锁',

    error_message VARCHAR(500) COMMENT '任务执行失败原因，例如文件解析失败、Embedding调用失败、向量库写入失败等',

    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    started_at DATETIME COMMENT '任务开始执行时间',

    finished_at DATETIME COMMENT '任务执行完成时间'
    ) COMMENT = '知识库向量化任务表，用于异步处理文档解析、切片、Embedding生成和向量入库任务';


CREATE INDEX idx_vector_task_status_created
    ON knowledge_base_vector_task (status, created_at);

CREATE INDEX idx_vector_task_kb_id
    ON knowledge_base_vector_task (knowledge_base_id);

CREATE INDEX idx_vector_task_lock
    ON knowledge_base_vector_task (lock_owner, locked_at);