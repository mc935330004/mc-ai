package org.example.ai.agent.common.task;

import org.example.ai.agent.modules.knowledgebase.mapper.KnowledgeBaseVectorTaskMapper;
import org.example.ai.agent.modules.knowledgebase.service.KnowledgeBaseVectorTaskService;
import org.example.ai.agent.modules.knowledgebase.service.KnowledgeDocumentVersionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

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
    void shouldPassClaimTokenWhenExecutingVectorization() {
        KnowledgeBaseVectorTaskWorker worker = new KnowledgeBaseVectorTaskWorker(taskService, taskMapper, versionService);

        ReflectionTestUtils.invokeMethod(worker, "process", 21L, 100L, "claim-1");

        verify(versionService).vectorizeVersion(21L, 100L, "claim-1");
    }

    @Test
    void shouldPersistVersionFailureBeforeSchedulingRetry() {
        doThrow(new RuntimeException("PGVector connection failed"))
                .when(versionService).vectorizeVersion(21L, 100L, "claim-1");
        when(versionService.markVectorizeFailed(
                21L, 100L, "claim-1", "PGVector connection failed"
        )).thenReturn(true);
        KnowledgeBaseVectorTaskWorker worker = new KnowledgeBaseVectorTaskWorker(taskService, taskMapper, versionService);

        ReflectionTestUtils.invokeMethod(worker, "process", 21L, 100L, "claim-1");

        verify(versionService).markVectorizeFailed(21L, 100L, "claim-1", "PGVector connection failed");
    }
}
