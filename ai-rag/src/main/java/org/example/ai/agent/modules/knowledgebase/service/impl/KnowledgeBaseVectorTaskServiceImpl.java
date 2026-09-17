package org.example.ai.agent.modules.knowledgebase.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.common.exception.ErrorCode;
import org.example.ai.agent.modules.knowledgebase.entity.KnowledgeBaseVectorTask;
import org.example.ai.agent.modules.knowledgebase.mapper.KnowledgeBaseVectorTaskMapper;
import org.example.ai.agent.modules.knowledgebase.model.VectorTaskStatus;
import org.example.ai.agent.modules.knowledgebase.service.KnowledgeBaseVectorTaskService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 知识库向量化任务服务。
 */
@Service
public class KnowledgeBaseVectorTaskServiceImpl
        extends ServiceImpl<KnowledgeBaseVectorTaskMapper, KnowledgeBaseVectorTask>
        implements KnowledgeBaseVectorTaskService {

    @Override
    public Long createDocumentVersionVectorizeTask(Long documentId, Long versionId) {
        if (documentId == null || versionId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文档ID和版本ID不能为空");
        }

        // 同一个文档版本存在活动任务时直接复用，避免重复提交。
        KnowledgeBaseVectorTask activeTask = this.getOne(
                Wrappers.<KnowledgeBaseVectorTask>lambdaQuery()
                        .eq(KnowledgeBaseVectorTask::getDocumentId, documentId)
                        .eq(KnowledgeBaseVectorTask::getVersionId, versionId)
                        .eq(KnowledgeBaseVectorTask::getTaskType, "VECTORIZE")
                        .in(KnowledgeBaseVectorTask::getStatus, List.of(
                                VectorTaskStatus.PENDING.name(),
                                VectorTaskStatus.PROCESSING.name()
                        ))
                        .orderByDesc(KnowledgeBaseVectorTask::getId)
                        .last("LIMIT 1"),
                false
        );
        if (activeTask != null) return activeTask.getId();

        LocalDateTime now = LocalDateTime.now();
        KnowledgeBaseVectorTask task = new KnowledgeBaseVectorTask();
        task.setDocumentId(documentId);
        task.setVersionId(versionId);
        task.setTaskType("VECTORIZE");
        task.setStatus(VectorTaskStatus.PENDING.name());
        task.setRetryCount(0);
        task.setMaxRetryCount(3);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);

        if (!this.save(task) || task.getId() == null) {
            throw new BusinessException(
                    ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED,
                    "创建文档向量化任务失败"
            );
        }
        return task.getId();
    }
}