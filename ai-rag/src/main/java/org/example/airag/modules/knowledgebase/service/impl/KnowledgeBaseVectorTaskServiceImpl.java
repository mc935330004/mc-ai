package org.example.airag.modules.knowledgebase.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.example.airag.common.exception.BusinessException;
import org.example.airag.common.exception.ErrorCode;
import org.example.airag.modules.knowledgebase.dto.VectorTaskDTO;
import org.example.airag.modules.knowledgebase.entity.KnowledgeBaseVectorTask;
import org.example.airag.modules.knowledgebase.mapper.KnowledgeBaseVectorTaskMapper;
import org.example.airag.modules.knowledgebase.model.VectorStatus;
import org.example.airag.modules.knowledgebase.model.VectorTaskStatus;
import org.example.airag.modules.knowledgebase.service.KnowledgeBaseVectorTaskService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 知识库向量化任务 Service 实现类
 */
@Service
@RequiredArgsConstructor
public class KnowledgeBaseVectorTaskServiceImpl extends ServiceImpl<KnowledgeBaseVectorTaskMapper, KnowledgeBaseVectorTask>
        implements KnowledgeBaseVectorTaskService {

    @Override
    public Long createDocumentVersionVectorizeTask(Long documentId, Long versionId) {
        if (documentId == null || versionId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文档ID和版本ID不能为空");
        }
        // 创建向量化任务
        KnowledgeBaseVectorTask task = new KnowledgeBaseVectorTask();
        task.setDocumentId(documentId);
        task.setVersionId(versionId);
        task.setTaskType("VECTORIZE");
        task.setStatus(VectorTaskStatus.PENDING.name());
        task.setRetryCount(0);
        task.setMaxRetryCount(3);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        this.save(task);
        return task.getId();
    }

}