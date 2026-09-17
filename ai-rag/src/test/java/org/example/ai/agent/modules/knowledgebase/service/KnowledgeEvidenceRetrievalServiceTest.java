package org.example.ai.agent.modules.knowledgebase.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.modules.knowledgebase.dto.KnowledgeDocumentQueryRequest;
import org.example.ai.agent.modules.knowledgebase.entity.KnowledgeChunk;
import org.example.ai.agent.modules.knowledgebase.entity.KnowledgeDocument;
import org.example.ai.agent.modules.knowledgebase.entity.KnowledgeDocumentVersion;
import org.example.ai.agent.modules.knowledgebase.model.KnowledgeEvidence;
import org.example.ai.agent.modules.knowledgebase.security.KnowledgeAccessPrincipal;
import org.example.ai.agent.modules.knowledgebase.service.impl.KnowledgeBaseVectorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 制度证据检索测试。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeEvidenceRetrievalServiceTest {

    @Mock
    private KnowledgeDocumentService documentService;
    @Mock
    private KnowledgeDocumentVersionService versionService;
    @Mock
    private KnowledgeChunkService chunkService;
    @Mock
    private ObjectProvider<KnowledgeBaseVectorService> vectorServiceProvider;
    @Mock
    private KnowledgeBaseVectorService vectorService;

    private KnowledgeEvidenceRetrievalService retrievalService;

    @BeforeEach
    void setUp() {
        retrievalService = new KnowledgeEvidenceRetrievalService(documentService, versionService, chunkService, vectorServiceProvider);
    }

    @Test
    void shouldReturnOnlyCurrentEffectiveAuthorizedEvidence() {
        LocalDateTime now = LocalDateTime.now();
        KnowledgeDocument allowed = document(1L, 10L, 100L, "PUBLIC", null, "PUBLISHED", "付款管理制度");
        KnowledgeDocument foreignTenant = document(2L, 20L, 200L, "PUBLIC", null, "PUBLISHED", "其他租户制度");
        KnowledgeDocument foreignDepartment = document(1L, 30L, 300L, "DEPARTMENT", 99L, "PUBLISHED", "其他部门制度");
        KnowledgeDocument draft = document(1L, 40L, 400L, "PUBLIC", null, "DRAFT", "制度草稿");
        KnowledgeDocument expired = document(1L, 50L, 500L, "PUBLIC", null, "PUBLISHED", "已失效制度");
        KnowledgeDocumentVersion currentVersion = version(100L, 10L, "v2.0", now.minusDays(1), now.plusDays(1));
        KnowledgeDocumentVersion expiredVersion = version(500L, 50L, "v1.0", now.minusDays(2), now.minusDays(1));
        KnowledgeChunk enabledChunk = chunk(1000L, 10L, 100L, 1, "单笔付款超过十万元必须完成财务复核。", 1);
        KnowledgeChunk disabledChunk = chunk(1001L, 10L, 100L, 2, "已禁用片段", 0);

        when(documentService.list(any(Wrapper.class))).thenReturn(List.of(allowed, foreignTenant, foreignDepartment, draft, expired));
        when(versionService.listByIds(anyCollection())).thenReturn(List.of(currentVersion, expiredVersion));
        when(vectorServiceProvider.getIfAvailable()).thenReturn(vectorService);
        when(vectorService.similaritySearchByVersionIds(eq("付款是否符合制度"), eq(List.of(100L)), eq(5), eq(0.2)))
                .thenReturn(List.of(vectorDocument(1000L, 10L, 100L, "有效片段"), vectorDocument(1001L, 10L, 100L, "禁用片段")));
        when(chunkService.list(any(Wrapper.class))).thenReturn(List.of(enabledChunk, disabledChunk));

        List<KnowledgeEvidence> result = retrievalService.retrieve(request(), new KnowledgeAccessPrincipal("user-1", 1L, 8L));

        assertThat(result).singleElement().satisfies(evidence -> {
            assertThat(evidence.evidenceId()).isEqualTo("evidence:100:1000");
            assertThat(evidence.documentId()).isEqualTo(10L);
            assertThat(evidence.versionId()).isEqualTo(100L);
            assertThat(evidence.chunkId()).isEqualTo(1000L);
            assertThat(evidence.chunkIndex()).isZero();
            assertThat(evidence.documentTitle()).isEqualTo("付款管理制度");
            assertThat(evidence.versionNo()).isEqualTo("v2.0");
            assertThat(evidence.text()).isEqualTo("单笔付款超过十万元必须完成财务复核。");
        });
        verify(vectorService).similaritySearchByVersionIds("付款是否符合制度", List.of(100L), 5, 0.2);
    }

    @Test
    void shouldIgnoreVectorHitWhoseMetadataDoesNotMatchCurrentVersion() {
        LocalDateTime now = LocalDateTime.now();
        KnowledgeDocument document = document(1L, 10L, 100L, "PUBLIC", null, "PUBLISHED", "付款管理制度");
        KnowledgeDocumentVersion version = version(100L, 10L, "v2.0", now.minusDays(1), now.plusDays(1));
        KnowledgeChunk chunk = chunk(1000L, 10L, 100L, 1, "有效片段", 1);

        when(documentService.list(any(Wrapper.class))).thenReturn(List.of(document));
        when(versionService.listByIds(anyCollection())).thenReturn(List.of(version));
        when(vectorServiceProvider.getIfAvailable()).thenReturn(vectorService);
        when(vectorService.similaritySearchByVersionIds(anyString(), anyList(), eq(5), eq(0.2)))
                .thenReturn(List.of(vectorDocument(1000L, 10L, 999L, "伪造版本片段")));
        when(chunkService.list(any(Wrapper.class))).thenReturn(List.of(chunk));

        List<KnowledgeEvidence> result = retrievalService.retrieve(request(), new KnowledgeAccessPrincipal("user-1", 1L, 8L));

        assertThat(result).isEmpty();
    }

    @Test
    void shouldRejectMissingTrustedPrincipal() {
        assertThatThrownBy(() -> retrievalService.retrieve(request(), null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("登录身份");
    }

    @Test
    void shouldNotReturnDepartmentEvidenceWhenPrincipalHasNoDepartment() {
        KnowledgeDocument departmentDocument = document(1L, 10L, 100L, "DEPARTMENT", 8L, "PUBLISHED", "部门制度");
        when(documentService.list(any(Wrapper.class))).thenReturn(List.of(departmentDocument));

        List<KnowledgeEvidence> result = retrievalService.retrieve(request(), new KnowledgeAccessPrincipal("user-1", 1L, null));

        assertThat(result).isEmpty();
        verifyNoInteractions(versionService, vectorService);
    }

    private KnowledgeDocumentQueryRequest request() {
        return new KnowledgeDocumentQueryRequest(List.of(), List.of(), "付款是否符合制度", 5, 0.2);
    }

    private KnowledgeDocument document(Long tenantId, Long id, Long versionId, String scope, Long deptId, String status, String title) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setTenantId(tenantId);
        document.setId(id);
        document.setCurrentVersionId(versionId);
        document.setAccessScope(scope);
        document.setOwnerDeptId(deptId);
        document.setStatus(status);
        document.setTitle(title);
        document.setDelFlag(0);
        return document;
    }

    private KnowledgeDocumentVersion version(Long id, Long documentId, String versionNo, LocalDateTime start, LocalDateTime end) {
        KnowledgeDocumentVersion version = new KnowledgeDocumentVersion();
        version.setId(id);
        version.setDocumentId(documentId);
        version.setVersionNo(versionNo);
        version.setOriginalFilename("付款管理制度.pdf");
        version.setParseStatus("COMPLETED");
        version.setVectorStatus("COMPLETED");
        version.setPublishedAt(LocalDateTime.now().minusHours(1));
        version.setEffectiveStartTime(start);
        version.setEffectiveEndTime(end);
        version.setDelFlag(0);
        return version;
    }

    private KnowledgeChunk chunk(Long id, Long documentId, Long versionId, Integer pageNumber, String content, Integer enabled) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setId(id);
        chunk.setDocumentId(documentId);
        chunk.setVersionId(versionId);
        chunk.setChunkIndex(0);
        chunk.setPageNumber(pageNumber);
        chunk.setContent(content);
        chunk.setEnabled(enabled);
        chunk.setDelFlag(0);
        return chunk;
    }

    private Document vectorDocument(Long chunkId, Long documentId, Long versionId, String text) {
        return new Document(text, Map.of(
                "chunk_id", chunkId.toString(),
                "document_id", documentId.toString(),
                "version_id", versionId.toString(),
                "chunk_index", "0",
                "source", "付款管理制度.pdf"
        ));
    }
}
