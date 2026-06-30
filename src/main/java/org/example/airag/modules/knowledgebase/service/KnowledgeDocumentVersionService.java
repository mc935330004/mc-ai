package org.example.airag.modules.knowledgebase.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.example.airag.modules.knowledgebase.entity.KnowledgeDocumentVersion;

/**
 * 企业知识文档版本向量化服务。
 *
 * 第二阶段核心服务：
 * 负责把 knowledge_document_version 中的原始文件解析、切片、入库、写入向量库。
 */
public interface KnowledgeDocumentVersionService extends IService<KnowledgeDocumentVersion> {
    /**
     * 执行指定文档版本的向量化。
     *
     * @param versionId 文档版本ID，对应 knowledge_document_version.id
     */
    void vectorizeVersion(Long versionId);
}
