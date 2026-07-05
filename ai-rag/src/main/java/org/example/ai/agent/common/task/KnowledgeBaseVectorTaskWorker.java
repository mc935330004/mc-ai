package org.example.ai.agent.common.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.modules.knowledgebase.entity.KnowledgeBaseVectorTask;
import org.example.ai.agent.modules.knowledgebase.mapper.KnowledgeBaseVectorTaskMapper;
import org.example.ai.agent.modules.knowledgebase.model.VectorTaskStatus;
import org.example.ai.agent.modules.knowledgebase.service.KnowledgeBaseVectorTaskService;
import org.example.ai.agent.modules.knowledgebase.service.KnowledgeDocumentVersionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@ConditionalOnProperty(prefix = "app.vector-task", name = "enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class KnowledgeBaseVectorTaskWorker {
    private final KnowledgeBaseVectorTaskService taskService;
    private final KnowledgeBaseVectorTaskMapper taskMapper;
    private final KnowledgeDocumentVersionService knowledgeDocumentVersionService;

    private final String workerId = buildWorkerId();
    @Value("${app.vector-task.timeout-minutes:30}")
    private int timeoutMinutes;
    /**
     * 轮询任务
     */
    @Scheduled(fixedDelayString = "${app.vector-task.poll-delay-ms:5000}")
    public void consume() {
        List<KnowledgeBaseVectorTask> tasks = taskService.lambdaQuery()
                .eq(KnowledgeBaseVectorTask::getStatus, VectorTaskStatus.PENDING.name())
                .orderByAsc(KnowledgeBaseVectorTask::getCreatedAt)
                .last("LIMIT 5")
                .list();
        for (KnowledgeBaseVectorTask task : tasks) {
            int locked = taskMapper.lockPendingTask(task.getId(), workerId);
            if (locked == 1) {
                process(task.getId());
            }
        }
    }

    /**
     * 恢复超时任务
     */
    @Scheduled(fixedDelayString = "${app.vector-task.recover-delay-ms:60000}")
    public void recoverTimeoutTasks() {
        int failed = taskMapper.failTimeoutTasks(timeoutMinutes);
        int reset = taskMapper.resetTimeoutTasks(timeoutMinutes);
        if (reset > 0 || failed > 0) {
            log.warn("恢复卡死向量化任务: reset={}, failed={}", reset, failed);
        }
    }
    /**
     * 处理任务
     * @param taskId
     */
    private void process(Long taskId) {
        KnowledgeBaseVectorTask task = taskService.getById(taskId);
        if (task == null) {
            return;
        }
        try {
            if (task.getVersionId() != null) {
                // 新企业文档版本流程
                knowledgeDocumentVersionService.vectorizeVersion(task.getVersionId());
            }
            markCompleted(task);
        } catch (Exception e) {
            markFailedOrRetry(task, e);
        }
    }

    /**
     * 标记任务完成
     * @param task
     */
    private void markCompleted(KnowledgeBaseVectorTask task) {
        task.setStatus(VectorTaskStatus.COMPLETED.name());
        task.setFinishedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        task.setErrorMessage(null);
        taskService.updateById(task);

        log.info("知识库向量化任务完成: taskId={}, kbId={}", task.getId(), task.getVersionId());
    }

    /**
     * 标记任务失败
     * @param task
     * @param e
     */
    private void markFailedOrRetry(KnowledgeBaseVectorTask task, Exception e) {
        int retryCount = task.getRetryCount() == null ? 1 : task.getRetryCount() + 1;
        int maxRetryCount = task.getMaxRetryCount() == null ? 3 : task.getMaxRetryCount();

        task.setRetryCount(retryCount);
        task.setErrorMessage(truncate(e.getMessage()));

        if (retryCount >= maxRetryCount) {
            task.setStatus(VectorTaskStatus.FAILED.name());
            task.setFinishedAt(LocalDateTime.now());
        } else {
            task.setStatus(VectorTaskStatus.PENDING.name());
            task.setLockOwner(null);
            task.setLockedAt(null);
            task.setStartedAt(null);
        }
        task.setUpdatedAt(LocalDateTime.now());
        taskService.updateById(task);
        log.warn("知识库向量化任务失败: taskId={}, kbId={}, retry={}/{}, error={}",
                task.getId(), task.getVersionId(), retryCount, maxRetryCount, e.getMessage(), e);
    }

    /**
     * 截取错误信息
     * @param message
     * @return
     */
    private String truncate(String message) {
        if (message == null || message.isBlank()) {
            return "unknown error";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
    /**
     * 构建workerId
     * @return
     */
    private String buildWorkerId() {
        try {
            return InetAddress.getLocalHost().getHostName() + "-" + UUID.randomUUID();
        } catch (Exception e) {
            return "worker-" + UUID.randomUUID();
        }
    }
}
