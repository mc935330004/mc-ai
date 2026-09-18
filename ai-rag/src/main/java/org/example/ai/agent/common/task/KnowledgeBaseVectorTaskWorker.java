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

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.vector-task", name = "enabled", havingValue = "true", matchIfMissing = true)
public class KnowledgeBaseVectorTaskWorker {

    private final KnowledgeBaseVectorTaskService taskService;
    private final KnowledgeBaseVectorTaskMapper taskMapper;
    private final KnowledgeDocumentVersionService versionService;

    @Value("${app.vector-task.timeout-minutes:30}")
    private int timeoutMinutes;

    /**
     * 轮询并领取待处理的向量任务。
     */
    @Scheduled(fixedDelayString = "${app.vector-task.poll-delay-ms:5000}")
    public void consume() {
        List<KnowledgeBaseVectorTask> tasks = taskService.lambdaQuery()
                .eq(KnowledgeBaseVectorTask::getStatus, VectorTaskStatus.PENDING.name())
                .orderByAsc(KnowledgeBaseVectorTask::getCreatedAt)
                .last("LIMIT 5")
                .list();

        for (KnowledgeBaseVectorTask task : tasks) {
            String claimToken = createClaimToken();
            int claimed = taskMapper.lockPendingTask(task.getId(), claimToken);
            if (claimed == 1) process(task.getId(), task.getVersionId(), claimToken);
        }
    }

    /**
     * 恢复超过处理时间的任务。
     */
    @Scheduled(fixedDelayString = "${app.vector-task.recover-delay-ms:60000}")
    public void recoverTimeoutTasks() {
        int failed = taskMapper.failTimeoutTasks(timeoutMinutes);
        int reset = taskMapper.resetTimeoutTasks(timeoutMinutes);
        if (reset > 0 || failed > 0) {
            log.warn("恢复超时向量任务: reset={}, failed={}", reset, failed);
        }
    }

    /**
     * 处理当前领取批次。
     */
    private void process(Long taskId, Long versionId, String claimToken) {
        try {
            versionService.vectorizeVersion(taskId, versionId, claimToken);
            log.info("知识库向量任务完成: taskId={}, versionId={}", taskId, versionId);
        } catch (Exception exception) {
            handleFailure(taskId, versionId, claimToken, exception);
        }
    }

    /**
     * 保存当前领取批次的失败状态。
     */
    private void handleFailure(Long taskId, Long versionId, String claimToken, Exception exception) {
        String errorMessage = truncate(exception.getMessage());
        try {
            boolean updated = versionService.markVectorizeFailed(
                    taskId,
                    versionId,
                    claimToken,
                    errorMessage
            );

            if (!updated) {
                log.warn("忽略过期领取批次的失败结果: taskId={}, versionId={}, claimToken={}",
                        taskId, versionId, claimToken);
                return;
            }

            log.warn("知识库向量任务执行失败: taskId={}, versionId={}, error={}",
                    taskId, versionId, errorMessage, exception);
        } catch (Exception stateException) {
            log.error("保存向量任务失败状态异常: taskId={}, versionId={}, error={}",
                    taskId, versionId, stateException.getMessage(), stateException);
        }
    }

    /**
     * 每次领取都生成新的代次令牌，防止旧执行器迟到写入。
     */
    private String createClaimToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 限制持久化错误信息长度。
     */
    private String truncate(String message) {
        if (message == null || message.isBlank()) return "unknown error";
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}