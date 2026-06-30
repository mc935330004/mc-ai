package org.example.airag.modules.knowledgebase.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.example.airag.common.exception.BusinessException;
import org.example.airag.common.exception.ErrorCode;
import org.example.airag.modules.knowledgebase.dto.VectorTaskDTO;
import org.example.airag.modules.knowledgebase.entity.KnowledgeBase;
import org.example.airag.modules.knowledgebase.entity.KnowledgeBaseVectorTask;
import org.example.airag.modules.knowledgebase.mapper.KnowledgeBaseVectorTaskMapper;
import org.example.airag.modules.knowledgebase.model.VectorStatus;
import org.example.airag.modules.knowledgebase.model.VectorTaskStatus;
import org.example.airag.modules.knowledgebase.service.KnowledgeBaseService;
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

    private final KnowledgeBaseService knowledgeBaseService;
    @Override
    public void createVectorizeTask(Long knowledgeBaseId) {
        KnowledgeBaseVectorTask task = new KnowledgeBaseVectorTask();
        task.setKnowledgeBaseId(knowledgeBaseId);
        task.setTaskType("VECTORIZE");
        task.setStatus(VectorTaskStatus.PENDING.name());
        task.setRetryCount(0);
        task.setMaxRetryCount(3);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        this.save(task);
    }

    @Override
    public List<VectorTaskDTO> listTasks(String status, Long knowledgeBaseId) {
        return lambdaQuery()
                .eq(status != null && !status.isBlank(),
                        KnowledgeBaseVectorTask::getStatus,
                        status.trim().toUpperCase())
                .eq(knowledgeBaseId != null,
                        KnowledgeBaseVectorTask::getKnowledgeBaseId,
                        knowledgeBaseId)
                .orderByDesc(KnowledgeBaseVectorTask::getCreatedAt)
                .last("LIMIT 100")
                .list()
                .stream()
                .map(this::toDTO)
                .toList();
    }

    /**
     *  重试向量化任务
     * @param taskId
     */
    @Override
    public void retryTask(Long taskId) {
        KnowledgeBaseVectorTask task = getById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "向量化任务不存在");
        }
        if (!VectorTaskStatus.FAILED.name().equals(task.getStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "只有失败任务可以手动重试");
        }
        // 更新知识库向量化状态
        KnowledgeBase kb = knowledgeBaseService.getById(task.getKnowledgeBaseId());
        if (kb != null) {
            kb.setVectorStatus(VectorStatus.PENDING.name());
            kb.setVectorError(null);
            kb.setUpdatedAt(LocalDateTime.now());
            knowledgeBaseService.updateById(kb);
        }
        task.setStatus(VectorTaskStatus.PENDING.name());
        task.setRetryCount(0);
        task.setLockOwner(null);
        task.setLockedAt(null);
        task.setStartedAt(null);
        task.setFinishedAt(null);
        task.setErrorMessage(null);
        task.setUpdatedAt(LocalDateTime.now());
        this.updateById(task);
    }

    @Override
    public Long createDocumentVersionVectorizeTask(Long documentId, Long versionId) {
        if (documentId == null || versionId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文档ID和版本ID不能为空");
        }

        KnowledgeBaseVectorTask task = new KnowledgeBaseVectorTask();
        task.setKnowledgeBaseId(null);
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

    private VectorTaskDTO toDTO(KnowledgeBaseVectorTask task) {
        return new VectorTaskDTO(
                task.getId(),
                task.getKnowledgeBaseId(),
                task.getTaskType(),
                task.getStatus(),
                task.getRetryCount(),
                task.getMaxRetryCount(),
                task.getLockOwner(),
                task.getLockedAt(),
                task.getErrorMessage(),
                task.getCreatedAt(),
                task.getUpdatedAt(),
                task.getStartedAt(),
                task.getFinishedAt()
        );
    }
}