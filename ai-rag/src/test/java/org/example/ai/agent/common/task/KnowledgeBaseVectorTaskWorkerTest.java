package org.example.ai.agent.common.task;

import org.example.ai.agent.modules.knowledgebase.entity.KnowledgeBaseVectorTask;
import org.example.ai.agent.modules.knowledgebase.mapper.KnowledgeBaseVectorTaskMapper;
import org.example.ai.agent.modules.knowledgebase.model.VectorTaskStatus;
import org.example.ai.agent.modules.knowledgebase.service.KnowledgeBaseVectorTaskService;
import org.example.ai.agent.modules.knowledgebase.service.KnowledgeDocumentVersionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 向量任务执行和重试测试。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeBaseVectorTaskWorkerTest {

    @Mock
    private KnowledgeBaseVectorTaskService taskService;
    @Mock
    private KnowledgeBaseVectorTaskMapper taskMapper;
    @Mock
    private KnowledgeDocumentVersionService versionService;

    @Test
    void shouldUseStableTaskJobIdWhenExecutingVectorization() {
        KnowledgeBaseVectorTask task = task(21L, 100L);
        when(taskService.getById(21L)).thenReturn(task);
        KnowledgeBaseVectorTaskWorker worker = new KnowledgeBaseVectorTaskWorker(taskService, taskMapper, versionService);

        ReflectionTestUtils.invokeMethod(worker, "process", 21L);

        verify(versionService).vectorizeVersion(100L, "vector-task:21");
        assertThat(task.getStatus()).isEqualTo(VectorTaskStatus.COMPLETED.name());
    }

    @Test
    void shouldPersistVersionFailureBeforeSchedulingRetry() {
        KnowledgeBaseVectorTask task = task(21L, 100L);
        when(taskService.getById(21L)).thenReturn(task);
        doThrow(new RuntimeException("PGVector connection failed"))
                .when(versionService).vectorizeVersion(100L, "vector-task:21");
        when(taskService.updateById(any(KnowledgeBaseVectorTask.class))).thenReturn(true);
        KnowledgeBaseVectorTaskWorker worker = new KnowledgeBaseVectorTaskWorker(taskService, taskMapper, versionService);

        ReflectionTestUtils.invokeMethod(worker, "process", 21L);

        verify(versionService).markVectorizeFailed(100L, "PGVector connection failed");
        assertThat(task.getStatus()).isEqualTo(VectorTaskStatus.PENDING.name());
        assertThat(task.getRetryCount()).isEqualTo(1);
    }

    private KnowledgeBaseVectorTask task(Long taskId, Long versionId) {
        KnowledgeBaseVectorTask task = new KnowledgeBaseVectorTask();
        task.setId(taskId);
        task.setDocumentId(10L);
        task.setVersionId(versionId);
        task.setStatus(VectorTaskStatus.PROCESSING.name());
        task.setRetryCount(0);
        task.setMaxRetryCount(3);
        return task;
    }
}
