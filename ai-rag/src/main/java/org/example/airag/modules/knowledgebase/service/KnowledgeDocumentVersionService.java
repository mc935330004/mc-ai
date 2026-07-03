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

    /**
     * 发布指定文档版本。
     *
     * 企业级 RAG 中，向量化完成不等于立即生效。
     * 只有发布后的版本，才能作为正式问答的检索来源。
     *
     * @param documentId 文档ID
     * @param versionId 文档版本ID
     */
    void publishVersion(Long documentId, Long versionId);

    /**
     * 重新向量化指定文档版本。
     *
     * 用于文档内容、切片策略或向量写入异常后的手动重建。
     */
    void revectorizeVersion(Long documentId, Long versionId);
}
