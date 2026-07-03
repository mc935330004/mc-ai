package org.example.airag.modules.knowledgebase.service;

import org.example.airag.modules.knowledgebase.dto.KnowledgeDocumentUploadRequest;
import org.example.airag.modules.knowledgebase.dto.KnowledgeDocumentUploadResponse;

public interface KnowledgeDocumentUploadService {

    /**
     * 上传企业知识文档，并创建文档版本和向量化任务。
     */
    KnowledgeDocumentUploadResponse upload(KnowledgeDocumentUploadRequest request);

}
