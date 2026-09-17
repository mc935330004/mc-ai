package org.example.ai.agent.modules.knowledgebase.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.common.exception.ErrorCode;
import org.example.ai.agent.common.file.ContentHashService;
import org.example.ai.agent.common.file.DocumentParseService;
import org.example.ai.agent.modules.knowledgebase.entity.KnowledgeChunk;
import org.example.ai.agent.modules.knowledgebase.entity.KnowledgeDocument;
import org.example.ai.agent.modules.knowledgebase.entity.KnowledgeDocumentVersion;
import org.example.ai.agent.modules.knowledgebase.mapper.KnowledgeChunkMapper;
import org.example.ai.agent.modules.knowledgebase.mapper.KnowledgeDocumentVersionMapper;
import org.example.ai.agent.modules.knowledgebase.repository.VectorRepository;
import org.example.ai.agent.modules.knowledgebase.service.FileStorageService;
import org.example.ai.agent.modules.knowledgebase.service.KnowledgeBaseVectorTaskService;
import org.example.ai.agent.modules.knowledgebase.service.KnowledgeDocumentService;
import org.example.ai.agent.modules.knowledgebase.service.KnowledgeDocumentVersionService;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.example.ai.agent.modules.knowledgebase.security.KnowledgeAccessContext;
import org.example.ai.agent.modules.knowledgebase.security.KnowledgeAccessPrincipal;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeDocumentVersionServiceImpl extends ServiceImpl<KnowledgeDocumentVersionMapper, KnowledgeDocumentVersion>
        implements KnowledgeDocumentVersionService {
    private static final int MAX_BATCH_SIZE = 10;

    private final KnowledgeDocumentService documentService;
    private final KnowledgeChunkMapper chunkMapper;
    private final FileStorageService fileStorageService;
    private final DocumentParseService documentParseService;
    private final ContentHashService contentHashService;
    private final ObjectProvider<VectorStore> vectorStoreProvider;
    private final VectorRepository vectorRepository;
    private final KnowledgeBaseVectorTaskService vectorTaskService;
    /**
     * 管理端发布和重新向量化时使用当前租户身份。
     */
    private final KnowledgeAccessContext knowledgeAccessContext;
    /**
     * TokenTextSplitter 是 Spring AI 提供的切片器。
     * 后续如果你要按标题、段落、页码做企业级切片，可以在这里替换为自定义切片策略。
     */
    private final TextSplitter textSplitter = TokenTextSplitter.builder().build();
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void vectorizeVersion(Long versionId, String vectorJobId) {
        if (!StringUtils.hasText(vectorJobId)) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "向量任务幂等标识不能为空"
            );
        }
        KnowledgeDocumentVersion version = getActiveVersion(versionId);
        KnowledgeDocument document = documentService.getOne(
                Wrappers.<KnowledgeDocument>lambdaQuery()
                        .eq(KnowledgeDocument::getId, version.getDocumentId())
                        .eq(KnowledgeDocument::getDelFlag, 0)
        );
        if (document == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在");
        }
        try {
            markProcessing(version);

            String content = parseVersionContent(version);
            List<Document> splitDocuments = splitContent(content);

            // 当前事务失败时MySQL切片会回滚，PGVector临时数据通过稳定jobId清理。
            deleteOldChunksAndVectors(version.getId());
            List<KnowledgeChunk> chunks = saveChunks(document, version, splitDocuments);
            writeVectors(document, version, chunks, vectorJobId);
            markCompleted(version, chunks.size());

            log.info(
                    "文档版本向量化完成: documentId={}, versionId={}, vectorJobId={}, chunkCount={}",
                    document.getId(),
                    version.getId(),
                    vectorJobId,
                    chunks.size()
            );
        } catch (Exception exception) {
            // 这里不能保存FAILED状态，否则会随当前事务回滚。
            log.error(
                    "文档版本向量化事务失败: documentId={}, versionId={}, vectorJobId={}, error={}",
                    document.getId(),
                    version.getId(),
                    vectorJobId,
                    exception.getMessage(),
                    exception
            );
            throw exception;
        }
    }

    /**
     * 发布文档版本
     * @param documentId
     * @param versionId
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void publishVersion(Long documentId, Long versionId) {
        if (documentId == null || versionId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文档ID和版本ID不能为空");
        }
        KnowledgeDocument document =getManagedDocument(documentId);
        if (document == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在");
        }
        KnowledgeDocumentVersion version = getActiveVersion(versionId);
        // 防止把 A 文档的版本发布到 B 文档上。
        if (!document.getId().equals(version.getDocumentId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文档版本不属于当前文档");
        }
        LocalDateTime now = LocalDateTime.now();
        validatePublishable(version, now);
        // 如果已有当前版本，发布新版本时把旧版本标记为废止。
        Long oldCurrentVersionId = document.getCurrentVersionId();
        if (oldCurrentVersionId != null && !oldCurrentVersionId.equals(versionId)) {
            KnowledgeDocumentVersion oldVersion = this.getById(oldCurrentVersionId);
            if (oldVersion != null) {
                oldVersion.setDeprecatedAt(now);
                oldVersion.setUpdatedAt(now);
                this.updateById(oldVersion);
            }
        }
        // 发布当前版本。
        version.setPublishedAt(now);
        version.setDeprecatedAt(null);
        version.setUpdatedAt(now);
        this.updateById(version);
        // 文档当前生效版本切换到本次发布版本。
        document.setStatus("PUBLISHED");
        document.setCurrentVersionId(versionId);
        document.setUpdatedAt(now);
        documentService.updateById(document);
    }

    /**
     * 只有完整且当前有效的版本才能切换为当前版本。
     */
    private void validatePublishable(KnowledgeDocumentVersion version, LocalDateTime now) {
        if (!"COMPLETED".equals(version.getParseStatus())
                || !"COMPLETED".equals(version.getVectorStatus())
                || version.getChunkCount() == null
                || version.getChunkCount() <= 0) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "只有解析和向量化完成的版本才能发布"
            );
        }
        if (version.getEffectiveStartTime() != null
                && version.getEffectiveStartTime().isAfter(now)) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "文档版本尚未到生效时间"
            );
        }
        if (version.getEffectiveEndTime() != null
                && version.getEffectiveEndTime().isBefore(now)) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "已失效的文档版本不能发布"
            );
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revectorizeVersion(Long documentId, Long versionId) {
        if (documentId == null || versionId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文档ID和版本ID不能为空");
        }
        KnowledgeDocument document =getManagedDocument(documentId);
        if (document == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在");
        }
        KnowledgeDocumentVersion version = getActiveVersion(versionId);

        // 防止把其他文档的版本拿来重建。
        if (!document.getId().equals(version.getDocumentId())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文档版本不属于当前文档");
        }
        /*
         * 当前生效版本不能原地删除切片和向量。
         * 需要调整内容或切片策略时上传新版本，成功后再切换。
         */
        if (versionId.equals(document.getCurrentVersionId())) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "当前生效版本不能原地重新向量化，请上传新版本后重新发布"
            );
        }
        // 重置版本状态，worker 会重新解析、切片、写向量。
        version.setParseStatus("PENDING");
        version.setVectorStatus("PENDING");
        version.setVectorError(null);
        version.setChunkCount(0);
        version.setUpdatedAt(LocalDateTime.now());
        this.updateById(version);
        // 创建异步向量化任务。
        vectorTaskService.createDocumentVersionVectorizeTask(documentId, versionId);
    }

    /**
     * 获取未删除的文档版本。
     */
    private KnowledgeDocumentVersion getActiveVersion(Long versionId) {
        if (versionId == null || versionId <= 0) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "文档版本ID必须大于0"
            );
        }
        KnowledgeDocumentVersion version = this.getById(versionId);
        if (version == null || !Integer.valueOf(0).equals(version.getDelFlag())) {
            throw new BusinessException(
                    ErrorCode.NOT_FOUND,
                    "文档版本不存在"
            );
        }
        return version;
    }

    /**
     * 标记文档版本为处理中
     * @param version
     */
    private void markProcessing(KnowledgeDocumentVersion version) {
        version.setParseStatus("PROCESSING");
        version.setVectorStatus("PROCESSING");
        version.setVectorError(null);
        version.setUpdatedAt(LocalDateTime.now());
        this.updateById(version);
    }

    /**
     * 解析文档版本内容
     * @param version
     * @return
     */
    private String parseVersionContent(KnowledgeDocumentVersion version) {
        byte[] fileBytes = fileStorageService.downloadFile(version.getStoragePath());
        return documentParseService.parseContent(
                fileBytes,
                version.getOriginalFilename()
        );
    }

    /**
     * 查询当前管理员所属租户的文档。
     */
    private KnowledgeDocument getManagedDocument(Long documentId) {
        if (documentId == null || documentId <= 0) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "文档ID必须大于0"
            );
        }

        KnowledgeAccessPrincipal principal = knowledgeAccessContext.getRequiredPrincipal();
        KnowledgeDocument document = documentService.getOne(
                Wrappers.<KnowledgeDocument>lambdaQuery()
                        .eq(KnowledgeDocument::getId, documentId)
                        .eq(KnowledgeDocument::getTenantId, principal.tenantId())
                        .eq(KnowledgeDocument::getDelFlag, 0)
        );
        if (document != null) return document;

        throw new BusinessException(
                ErrorCode.NOT_FOUND,
                "文档不存在"
        );
    }

    /**
     * 切分文档内容
     * @param content
     * @return
     */
    private List<Document> splitContent(String content) {
        List<Document> documents = textSplitter.apply(List.of(new Document(content)));
        if (documents == null || documents.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED,
                    "文档切片结果为空"
            );
        }
        return documents;
    }

    /**
     * 删除旧的切片和向量
     * @param versionId
     */
    private void deleteOldChunksAndVectors(Long versionId) {
        chunkMapper.delete(
                Wrappers.<KnowledgeChunk>lambdaQuery()
                        .eq(KnowledgeChunk::getVersionId, versionId)
        );
        vectorRepository.deleteByVersionId(versionId);
    }

    /**
     * 保存切片
     * @param document
     * @param version
     * @param splitDocuments
     * @return
     */
    private List<KnowledgeChunk> saveChunks(KnowledgeDocument document,KnowledgeDocumentVersion version,
            List<Document> splitDocuments) {
        List<KnowledgeChunk> chunks = new ArrayList<>();
        for (int i = 0; i < splitDocuments.size(); i++) {
            String text = splitDocuments.get(i).getText();
            if (!StringUtils.hasText(text)) {
                continue;
            }
            KnowledgeChunk chunk = new KnowledgeChunk();
            chunk.setDocumentId(document.getId());
            chunk.setVersionId(version.getId());
            chunk.setChunkIndex(i);
            chunk.setContent(text);
            chunk.setContentHash(contentHashService.sha256(text));
            chunk.setTokenCount(null);
            chunk.setPageNumber(null);
            chunk.setEnabled(1);
            chunk.setVectorId(null);
            chunk.setDelFlag(0);
            chunk.setCreatedAt(LocalDateTime.now());
            chunk.setUpdatedAt(LocalDateTime.now());
            chunkMapper.insert(chunk);
            chunks.add(chunk);
        }
        if (chunks.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED,
                    "有效切片数量为0"
            );
        }
        return chunks;
    }
    /**
     * 使用稳定任务标识写入向量。
     */
    private void writeVectors(KnowledgeDocument document, KnowledgeDocumentVersion version,
                              List<KnowledgeChunk> chunks, String vectorJobId) {
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null) {
            throw new BusinessException(
                    ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED,
                    "VectorStore 未启用，无法写入向量库"
            );
        }
        // 清除同一任务上一次异常中断后可能遗留的向量。
        vectorRepository.deleteByVectorJobId(vectorJobId);

        List<Document> vectorDocuments = new ArrayList<>();
        for (KnowledgeChunk chunk : chunks) {
            Document vectorDocument = new Document(chunk.getContent());
            vectorDocument.getMetadata().put("document_id", document.getId().toString());
            vectorDocument.getMetadata().put("version_id", version.getId().toString());
            vectorDocument.getMetadata().put("chunk_id", chunk.getId().toString());
            vectorDocument.getMetadata().put("chunk_index", chunk.getChunkIndex().toString());
            vectorDocument.getMetadata().put(
                    "category_id",
                    document.getCategoryId() == null ? "" : document.getCategoryId().toString()
            );
            vectorDocument.getMetadata().put("document_code", nullToEmpty(document.getDocumentCode()));
            vectorDocument.getMetadata().put("document_title", nullToEmpty(document.getTitle()));
            vectorDocument.getMetadata().put("source", nullToEmpty(version.getOriginalFilename()));
            vectorDocument.getMetadata().put("kb_vector_job_id", vectorJobId);
            vectorDocuments.add(vectorDocument);
        }

        try {
            for (int start = 0; start < vectorDocuments.size(); start += MAX_BATCH_SIZE) {
                int end = Math.min(start + MAX_BATCH_SIZE, vectorDocuments.size());
                vectorStore.add(vectorDocuments.subList(start, end));
            }
        } catch (Exception exception) {
            vectorRepository.deleteByVectorJobId(vectorJobId);
            throw new BusinessException(
                    ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED,
                    "写入向量库失败: " + exception.getMessage(),
                    exception
            );
        }
    }
    /**
     * 标记完成
     * @param version
     * @param chunkCount
     */
    private void markCompleted( KnowledgeDocumentVersion version, int chunkCount) {
        version.setParseStatus("COMPLETED");
        version.setVectorStatus("COMPLETED");
        version.setVectorError(null);
        version.setChunkCount(chunkCount);
        version.setUpdatedAt(LocalDateTime.now());
        this.updateById(version);
    }
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markVectorizeFailed(Long versionId, String errorMessage) {
        KnowledgeDocumentVersion version = getActiveVersion(versionId);
        version.setParseStatus("FAILED");
        version.setVectorStatus("FAILED");
        version.setVectorError(truncateError(errorMessage));
        version.setUpdatedAt(LocalDateTime.now());
        this.updateById(version);
    }

    /**
     * 截断错误信息
     * @param message
     * @return
     */
    private String truncateError(String message) {
        if (message == null || message.isBlank()) {
            return "未知错误";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    /**
     * 空字符串转换为空
     * @param value
     * @return
     */
    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
