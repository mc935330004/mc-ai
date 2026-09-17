package org.example.ai.agent.modules.knowledgebase.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.example.ai.agent.modules.knowledgebase.entity.KnowledgeBaseVectorTask;
import org.example.ai.agent.modules.knowledgebase.mapper.KnowledgeBaseVectorTaskMapper;
import org.example.ai.agent.modules.knowledgebase.model.VectorTaskStatus;
import org.example.ai.agent.modules.knowledgebase.service.impl.KnowledgeBaseVectorTaskServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 向量任务幂等创建测试。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeBaseVectorTaskServiceImplTest {

    @Mock
    private KnowledgeBaseVectorTaskMapper taskMapper;

    private KnowledgeBaseVectorTaskServiceImpl taskService;

    @BeforeEach
    void setUp() {
        taskService = new KnowledgeBaseVectorTaskServiceImpl();
        ReflectionTestUtils.setField(taskService, "baseMapper", taskMapper);
    }

    @Test
    void shouldReuseActiveTaskForSameDocumentVersion() {
        KnowledgeBaseVectorTask existing = new KnowledgeBaseVectorTask();
        existing.setId(21L);
        existing.setDocumentId(10L);
        existing.setVersionId(100L);
        existing.setStatus(VectorTaskStatus.PENDING.name());
        when(taskMapper.selectOne(any(Wrapper.class))).thenReturn(existing);

        Long taskId = taskService.createDocumentVersionVectorizeTask(10L, 100L);

        assertThat(taskId).isEqualTo(21L);
        verify(taskMapper, never()).insert(Collections.singleton(any()));
    }

    @Test
    void shouldCreateOneTaskWhenNoActiveTaskExists() {
        when(taskMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(taskMapper.insert(any(KnowledgeBaseVectorTask.class))).thenAnswer(invocation -> {
            KnowledgeBaseVectorTask task = invocation.getArgument(0);
            task.setId(22L);
            return 1;
        });

        Long taskId = taskService.createDocumentVersionVectorizeTask(10L, 100L);

        assertThat(taskId).isEqualTo(22L);
        verify(taskMapper).insert(any(KnowledgeBaseVectorTask.class));
    }
}
