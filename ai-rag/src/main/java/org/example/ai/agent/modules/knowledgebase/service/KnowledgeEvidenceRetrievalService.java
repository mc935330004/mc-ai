package org.example.ai.agent.modules.knowledgebase.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.common.exception.ErrorCode;
import org.example.ai.agent.modules.knowledgebase.dto.KnowledgeDocumentQueryRequest;
import org.example.ai.agent.modules.knowledgebase.entity.KnowledgeChunk;
import org.example.ai.agent.modules.knowledgebase.entity.KnowledgeDocument;
import org.example.ai.agent.modules.knowledgebase.entity.KnowledgeDocumentVersion;
import org.example.ai.agent.modules.knowledgebase.model.KnowledgeEvidence;
import org.example.ai.agent.modules.knowledgebase.security.KnowledgeAccessPrincipal;
import org.example.ai.agent.modules.knowledgebase.service.impl.KnowledgeBaseVectorService;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 知识库证据检索服务。
 *
 * 只负责权限过滤、版本过滤、向量召回和证据转换，
 * 不在这里生成最终业务结论。
 */
@Service
@RequiredArgsConstructor
public class KnowledgeEvidenceRetrievalService {

    private static final int DEFAULT_TOP_K = 5;
    private static final int MAX_TOP_K = 10;
    private static final int MAX_EVIDENCE_TEXT_LENGTH = 2000;
    private static final double DEFAULT_MIN_SCORE = 0.2;

    private final KnowledgeDocumentService documentService;
    private final KnowledgeDocumentVersionService versionService;
    private final KnowledgeChunkService chunkService;
    private final ObjectProvider<KnowledgeBaseVectorService> vectorServiceProvider;

    /**
     * 在可信登录身份允许的范围内检索证据。
     */
    public List<KnowledgeEvidence> retrieve(
            KnowledgeDocumentQueryRequest request,
            KnowledgeAccessPrincipal principal) {

        validateRequest(request);
        validatePrincipal(principal);

        LocalDateTime retrievedAt = LocalDateTime.now();
        List<KnowledgeDocument> documents = findAuthorizedDocuments(request, principal);
        if (documents.isEmpty()) return List.of();

        Map<Long, KnowledgeDocument> documentByVersionId = documents.stream()
                .collect(Collectors.toMap(
                        KnowledgeDocument::getCurrentVersionId,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));

        List<KnowledgeDocumentVersion> versions = findEffectiveVersions(documentByVersionId, retrievedAt);
        if (versions.isEmpty()) return List.of();

        Map<Long, KnowledgeDocumentVersion> versionById = versions.stream()
                .collect(Collectors.toMap(KnowledgeDocumentVersion::getId, Function.identity()));

        List<Long> versionIds = versions.stream().map(KnowledgeDocumentVersion::getId).toList();
        KnowledgeBaseVectorService vectorService = vectorServiceProvider.getIfAvailable();
        if (vectorService == null) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED, "向量检索服务未启用");
        }

        int topK = normalizeTopK(request.topK());
        double minScore = normalizeMinScore(request.minScore());
        List<Document> hits = vectorService.similaritySearchByVersionIds(
                request.question().trim(),
                versionIds,
                topK,
                minScore
        );
        if (hits == null || hits.isEmpty()) return List.of();

        Map<Long, KnowledgeChunk> chunkById = findEnabledChunks(hits);
        if (chunkById.isEmpty()) return List.of();

        return buildEvidence(
                hits,
                documentByVersionId,
                versionById,
                chunkById,
                retrievedAt,
                topK
        );
    }

    /**
     * 数据库条件负责减少查询数据，后续仍会再次执行 Java 权限校验。
     */
    private List<KnowledgeDocument> findAuthorizedDocuments(
            KnowledgeDocumentQueryRequest request,
            KnowledgeAccessPrincipal principal) {

        List<Long> categoryIds = positiveIds(request.categoryIds());
        List<Long> documentIds = positiveIds(request.documentIds());

        LambdaQueryWrapper<KnowledgeDocument> query = Wrappers.lambdaQuery(KnowledgeDocument.class)
                .eq(KnowledgeDocument::getTenantId, principal.tenantId())
                .eq(KnowledgeDocument::getDelFlag, 0)
                .eq(KnowledgeDocument::getStatus, "PUBLISHED")
                .isNotNull(KnowledgeDocument::getCurrentVersionId);

        if (!categoryIds.isEmpty()) {
            query.in(KnowledgeDocument::getCategoryId, categoryIds);
        }
        if (!documentIds.isEmpty()) {
            query.in(KnowledgeDocument::getId, documentIds);
        }

        query.and(scope -> {
            scope.eq(KnowledgeDocument::getAccessScope, "PUBLIC");
            if (validDepartment(principal.deptId())) {
                scope.or(department -> department
                        .eq(KnowledgeDocument::getAccessScope, "DEPARTMENT")
                        .eq(KnowledgeDocument::getOwnerDeptId, principal.deptId()));
            }
        });

        List<KnowledgeDocument> documents = documentService.list(query);
        if (documents == null || documents.isEmpty()) return List.of();

        // 对数据库返回结果再次复权，防止查询条件或模拟数据被错误放宽。
        return documents.stream()
                .filter(document -> authorized(document, request, principal))
                .toList();
    }

    private boolean authorized(
            KnowledgeDocument document,
            KnowledgeDocumentQueryRequest request,
            KnowledgeAccessPrincipal principal) {

        if (document == null || !principal.tenantId().equals(document.getTenantId())) return false;
        if (!Integer.valueOf(0).equals(document.getDelFlag())) return false;
        if (!"PUBLISHED".equals(document.getStatus()) || document.getCurrentVersionId() == null) return false;
        if (!matchesOptionalFilter(document.getCategoryId(), request.categoryIds())) return false;
        if (!matchesOptionalFilter(document.getId(), request.documentIds())) return false;
        if ("PUBLIC".equals(document.getAccessScope())) return true;

        return "DEPARTMENT".equals(document.getAccessScope())
                && validDepartment(principal.deptId())
                && principal.deptId().equals(document.getOwnerDeptId());
    }

    /**
     * 当前版本必须完成解析和向量化，并处于实际生效时间范围。
     */
    private List<KnowledgeDocumentVersion> findEffectiveVersions(
            Map<Long, KnowledgeDocument> documentByVersionId,
            LocalDateTime now) {

        List<KnowledgeDocumentVersion> versions = versionService.listByIds(documentByVersionId.keySet());
        if (versions == null || versions.isEmpty()) return List.of();

        return versions.stream()
                .filter(version -> effective(version, documentByVersionId, now))
                .toList();
    }

    private boolean effective(
            KnowledgeDocumentVersion version,
            Map<Long, KnowledgeDocument> documentByVersionId,
            LocalDateTime now) {

        if (version == null || version.getId() == null) return false;
        if (!Integer.valueOf(0).equals(version.getDelFlag())) return false;
        if (!"COMPLETED".equals(version.getParseStatus())) return false;
        if (!"COMPLETED".equals(version.getVectorStatus())) return false;
        if (version.getPublishedAt() == null || version.getPublishedAt().isAfter(now)) return false;
        if (version.getDeprecatedAt() != null) return false;
        if (version.getEffectiveStartTime() != null && version.getEffectiveStartTime().isAfter(now)) return false;
        if (version.getEffectiveEndTime() != null && version.getEffectiveEndTime().isBefore(now)) return false;

        KnowledgeDocument document = documentByVersionId.get(version.getId());
        return document != null && document.getId().equals(version.getDocumentId());
    }

    private Map<Long, KnowledgeChunk> findEnabledChunks(List<Document> hits) {
        List<Long> chunkIds = hits.stream()
                .map(hit -> metadataLong(hit, "chunk_id"))
                .filter(id -> id != null && id > 0)
                .distinct()
                .toList();
        if (chunkIds.isEmpty()) return Map.of();

        List<KnowledgeChunk> chunks = chunkService.list(
                Wrappers.lambdaQuery(KnowledgeChunk.class)
                        .in(KnowledgeChunk::getId, chunkIds)
                        .eq(KnowledgeChunk::getEnabled, 1)
                        .eq(KnowledgeChunk::getDelFlag, 0)
        );
        if (chunks == null || chunks.isEmpty()) return Map.of();

        return chunks.stream()
                .filter(chunk -> StringUtils.hasText(chunk.getContent()))
                .collect(Collectors.toMap(
                        KnowledgeChunk::getId,
                        Function.identity(),
                        (left, right) -> left
                ));
    }

    private List<KnowledgeEvidence> buildEvidence(
            List<Document> hits,
            Map<Long, KnowledgeDocument> documentByVersionId,
            Map<Long, KnowledgeDocumentVersion> versionById,
            Map<Long, KnowledgeChunk> chunkById,
            LocalDateTime retrievedAt,
            int topK) {

        Map<Long, KnowledgeEvidence> evidenceByChunkId = new LinkedHashMap<>();

        for (Document hit : hits) {
            KnowledgeEvidence evidence = toEvidence(
                    hit,
                    documentByVersionId,
                    versionById,
                    chunkById,
                    retrievedAt
            );
            if (evidence == null) continue;

            evidenceByChunkId.putIfAbsent(evidence.chunkId(), evidence);
            if (evidenceByChunkId.size() >= topK) break;
        }

        return List.copyOf(evidenceByChunkId.values());
    }

    /**
     * 使用 MySQL 切片内容作为最终证据文本，向量 metadata 只负责定位。
     */
    private KnowledgeEvidence toEvidence(
            Document hit,
            Map<Long, KnowledgeDocument> documentByVersionId,
            Map<Long, KnowledgeDocumentVersion> versionById,
            Map<Long, KnowledgeChunk> chunkById,
            LocalDateTime retrievedAt) {

        Long documentId = metadataLong(hit, "document_id");
        Long versionId = metadataLong(hit, "version_id");
        Long chunkId = metadataLong(hit, "chunk_id");
        if (documentId == null || versionId == null || chunkId == null) return null;

        KnowledgeDocument document = documentByVersionId.get(versionId);
        KnowledgeDocumentVersion version = versionById.get(versionId);
        KnowledgeChunk chunk = chunkById.get(chunkId);
        if (document == null || version == null || chunk == null) return null;
        if (!documentId.equals(document.getId())) return null;
        if (!documentId.equals(chunk.getDocumentId()) || !versionId.equals(chunk.getVersionId())) return null;

        return new KnowledgeEvidence(
                "evidence:" + versionId + ":" + chunkId,
                documentId,
                versionId,
                chunkId,
                chunk.getChunkIndex(),
                document.getTitle(),
                version.getVersionNo(),
                truncateText(chunk.getContent()),
                firstText(version.getOriginalFilename(), metadata(hit, "source")),
                chunk.getPageNumber(),
                normalizeScore(hit.getScore()),
                retrievedAt
        );
    }

    private void validateRequest(KnowledgeDocumentQueryRequest request) {
        if (request == null || !StringUtils.hasText(request.question())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "问题不能为空");
        }
    }

    private void validatePrincipal(KnowledgeAccessPrincipal principal) {
        if (principal == null || principal.tenantId() == null || !StringUtils.hasText(principal.userId())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "知识库查询缺少有效的登录身份");
        }
    }

    private int normalizeTopK(Integer topK) {
        if (topK == null || topK <= 0) return DEFAULT_TOP_K;
        return Math.min(topK, MAX_TOP_K);
    }

    private double normalizeMinScore(Double minScore) {
        if (minScore == null || !Double.isFinite(minScore)) return DEFAULT_MIN_SCORE;
        return Math.max(0, Math.min(minScore, 1));
    }

    private double normalizeScore(Double score) {
        if (score == null || !Double.isFinite(score)) return 0;
        return Math.max(0, Math.min(score, 1));
    }

    private boolean validDepartment(Long deptId) {
        return deptId != null && deptId > 0;
    }

    private boolean matchesOptionalFilter(Long value, List<Long> filter) {
        List<Long> normalized = positiveIds(filter);
        return normalized.isEmpty() || normalized.contains(value);
    }

    private List<Long> positiveIds(Collection<Long> values) {
        if (values == null || values.isEmpty()) return List.of();
        return values.stream().filter(value -> value != null && value > 0).distinct().toList();
    }

    private Long metadataLong(Document document, String key) {
        String value = metadata(document, key);
        if (!StringUtils.hasText(value)) return null;

        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String metadata(Document document, String key) {
        if (document == null || document.getMetadata() == null) return "";
        Object value = document.getMetadata().get(key);
        return value == null ? "" : value.toString().trim();
    }

    private String truncateText(String text) {
        if (!StringUtils.hasText(text)) return "";
        String normalized = text.trim();
        if (normalized.length() <= MAX_EVIDENCE_TEXT_LENGTH) return normalized;
        return normalized.substring(0, MAX_EVIDENCE_TEXT_LENGTH);
    }

    private String firstText(String first, String second) {
        if (StringUtils.hasText(first)) return first.trim();
        return StringUtils.hasText(second) ? second.trim() : "";
    }
}