package org.example.ai.agent.modules.knowledgebase.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.common.file.ContentHashService;
import org.example.ai.agent.common.file.DocumentParseService;
import org.example.ai.agent.modules.knowledgebase.entity.KnowledgeDocument;
import org.example.ai.agent.modules.knowledgebase.entity.KnowledgeDocumentVersion;
import org.example.ai.agent.modules.knowledgebase.mapper.KnowledgeChunkMapper;
import org.example.ai.agent.modules.knowledgebase.repository.VectorRepository;
import org.example.ai.agent.modules.knowledgebase.security.KnowledgeAccessContext;
import org.example.ai.agent.modules.knowledgebase.security.KnowledgeAccessPrincipal;
import org.example.ai.agent.modules.knowledgebase.service.impl.KnowledgeDocumentVersionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文档版本发布和失败状态测试。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeDocumentVersionServiceImplTest {

    @Mock
    private KnowledgeDocumentService documentService;
    @Mock
    private KnowledgeChunkMapper chunkMapper;
    @Mock
    private FileStorageService fileStorageService;
    @Mock
    private DocumentParseService documentParseService;
    @Mock
    private ContentHashService contentHashService;
    @Mock
    private ObjectProvider<VectorStore> vectorStoreProvider;
    @Mock
    private VectorRepository vectorRepository;
    @Mock
    private KnowledgeBaseVectorTaskService vectorTaskService;
    @Mock
    private KnowledgeAccessContext knowledgeAccessContext;

    private KnowledgeDocumentVersionServiceImpl versionService;

    @BeforeEach
    void setUp() {
        versionService = spy(new KnowledgeDocumentVersionServiceImpl(
                documentService,
                chunkMapper,
                fileStorageService,
                documentParseService,
                contentHashService,
                vectorStoreProvider,
                vectorRepository,
                vectorTaskService,
                knowledgeAccessContext
        ));
    }

    @Test
    void shouldKeepOldCurrentVersionWhenCandidateIsNotCompleted() {
        KnowledgeDocument document = document(10L, 90L);
        KnowledgeDocumentVersion candidate = version(100L, 10L, "COMPLETED", "FAILED");
        when(knowledgeAccessContext.getRequiredPrincipal()).thenReturn(new KnowledgeAccessPrincipal("admin-1", 1L, 8L));
        when(documentService.getOne(any(Wrapper.class))).thenReturn(document);
        doReturn(candidate).when(versionService).getById(100L);

        assertThatThrownBy(() -> versionService.publishVersion(10L, 100L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("解析和向量化完成");

        assertThat(document.getCurrentVersionId()).isEqualTo(90L);
        verify(documentService, never()).updateById(any());
    }

    @Test
    void shouldRejectRevectorizingCurrentPublishedVersionInPlace() {
        KnowledgeDocument document = document(10L, 100L);
        KnowledgeDocumentVersion current = version(100L, 10L, "COMPLETED", "COMPLETED");
        when(knowledgeAccessContext.getRequiredPrincipal()).thenReturn(new KnowledgeAccessPrincipal("admin-1", 1L, 8L));
        when(documentService.getOne(any(Wrapper.class))).thenReturn(document);
        doReturn(current).when(versionService).getById(100L);

        assertThatThrownBy(() -> versionService.revectorizeVersion(10L, 100L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("上传新版本");

        verify(vectorTaskService, never()).createDocumentVersionVectorizeTask(any(), any());
    }

    @Test
    void shouldPersistSafeFailureStateForWorkerRetry() {
        KnowledgeDocumentVersion version = version(100L, 10L, "PROCESSING", "PROCESSING");
        doReturn(version).when(versionService).getById(100L);
        doReturn(true).when(versionService).updateById(any(KnowledgeDocumentVersion.class));

        versionService.markVectorizeFailed(100L, "PGVector connection failed");

        assertThat(version.getParseStatus()).isEqualTo("FAILED");
        assertThat(version.getVectorStatus()).isEqualTo("FAILED");
        assertThat(version.getVectorError()).isEqualTo("PGVector connection failed");
        verify(versionService).updateById(version);
    }

    private KnowledgeDocument document(Long documentId, Long currentVersionId) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setId(documentId);
        document.setTenantId(1L);
        document.setCurrentVersionId(currentVersionId);
        document.setStatus("PUBLISHED");
        document.setDelFlag(0);
        return document;
    }

    private KnowledgeDocumentVersion version(Long versionId, Long documentId, String parseStatus, String vectorStatus) {
        KnowledgeDocumentVersion version = new KnowledgeDocumentVersion();
        version.setId(versionId);
        version.setDocumentId(documentId);
        version.setParseStatus(parseStatus);
        version.setVectorStatus(vectorStatus);
        version.setChunkCount(1);
        version.setEffectiveStartTime(LocalDateTime.now().minusDays(1));
        version.setEffectiveEndTime(LocalDateTime.now().plusDays(1));
        version.setDelFlag(0);
        return version;
    }
}
