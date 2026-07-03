package org.example.airag.modules.knowledgebase.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.example.airag.modules.knowledgebase.dto.VectorTaskDTO;
import org.example.airag.modules.knowledgebase.entity.KnowledgeBaseVectorTask;

import java.util.List;

/**
 * 知识库向量化任务 Service
 */
public interface KnowledgeBaseVectorTaskService extends IService<KnowledgeBaseVectorTask> {


    /**
     * 创建文档版本向量化任务。
     *
     * @param documentId 文档ID
     * @param versionId 文档版本ID
     * @return 向量化任务ID
     */
    Long createDocumentVersionVectorizeTask(Long documentId, Long versionId);
}