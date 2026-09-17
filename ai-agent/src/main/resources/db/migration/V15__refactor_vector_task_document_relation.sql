-- 企业文档向量任务已经通过 document_id 和 version_id 关联文档版本。
-- knowledge_base_id 已无生产代码使用，删除旧字段及旧索引。

ALTER TABLE knowledge_base_vector_task
DROP INDEX idx_vector_task_kb_id,
DROP COLUMN knowledge_base_id;

-- 支持按文档版本、任务类型和状态快速查找活动任务。
CREATE INDEX idx_vector_task_document_version_status
    ON knowledge_base_vector_task (
                                   document_id,
                                   version_id,
                                   task_type,
                                   status,
                                   id
        );