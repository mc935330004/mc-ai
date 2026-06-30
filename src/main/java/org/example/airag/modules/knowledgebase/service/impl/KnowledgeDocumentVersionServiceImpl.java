package org.example.airag.modules.knowledgebase.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.airag.common.exception.BusinessException;
import org.example.airag.common.exception.ErrorCode;
import org.example.airag.common.file.ContentHashService;
import org.example.airag.common.file.DocumentParseService;
import org.example.airag.modules.knowledgebase.entity.KnowledgeChunk;
import org.example.airag.modules.knowledgebase.entity.KnowledgeDocument;
import org.example.airag.modules.knowledgebase.entity.KnowledgeDocumentVersion;
import org.example.airag.modules.knowledgebase.mapper.KnowledgeChunkMapper;
import org.example.airag.modules.knowledgebase.mapper.KnowledgeDocumentVersionMapper;
import org.example.airag.modules.knowledgebase.repository.VectorRepository;
import org.example.airag.modules.knowledgebase.service.FileStorageService;
import org.example.airag.modules.knowledgebase.service.KnowledgeDocumentService;
import org.example.airag.modules.knowledgebase.service.KnowledgeDocumentVersionService;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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

    /**
     * TokenTextSplitter 是 Spring AI 提供的切片器。
     * 后续如果你要按标题、段落、页码做企业级切片，可以在这里替换为自定义切片策略。
     */
    private final TextSplitter textSplitter = TokenTextSplitter.builder().build();
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void vectorizeVersion(Long versionId) {

        KnowledgeDocumentVersion version = getActiveVersion(versionId);
        KnowledgeDocument document = getActiveDocument(version.getDocumentId());
        try {
            String content = parseVersionContent(version);
            List<Document> splitDocuments = splitContent(content);

            // 删除当前版本旧切片和旧向量，保证重试、重新向量化时结果干净。
            deleteOldChunksAndVectors(version.getId());
            // 保存切片
            List<KnowledgeChunk> chunks = saveChunks(document, version, splitDocuments);
            // 写入向量
            writeVectors(document, version, chunks);
            // 标记完成
            markCompleted(document, version, chunks.size());
            // 标记处理中
            log.info("文档版本向量化完成: documentId={}, versionId={}, chunkCount={}",
                    document.getId(), version.getId(), chunks.size());
        } catch (Exception e) {
            markFailed(version, e);
            throw e;
        }
        markProcessing(version);
    }

    /**
     * 获取有效的文档版本
     * @param versionId
     * @return
     */
    private KnowledgeDocumentVersion getActiveVersion(Long versionId) {
        if (versionId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文档版本ID不能为空");
        }

        KnowledgeDocumentVersion version = this.getById(versionId);
        if (version == null || Integer.valueOf(1).equals(version.getDelFlag())) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文档版本不存在");
        }
        return version;
    }
    /**
     * 获取有效的文档
     * @param documentId
     * @return
     */
    private KnowledgeDocument getActiveDocument(Long documentId) {
        KnowledgeDocument document = documentService.lambdaQuery()
                .eq(KnowledgeDocument::getId,documentId)
                .eq(KnowledgeDocument::getDelFlag,0).one();
        if (document == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在");
        }
        return document;
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
        String content = documentParseService.parseContent(
                fileBytes,
                version.getOriginalFilename()
        );
        if (!StringUtils.hasText(content)) {
            throw new BusinessException(
                    ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED,
                    "无法从文档版本中解析出有效文本"
            );
        }
        return content.trim();
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
            chunk.setContent(text.trim());
            chunk.setContentHash(contentHashService.sha256(text.trim()));
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
     * 写入向量
     * @param document
     * @param version
     * @param chunks
     */
    private void writeVectors( KnowledgeDocument document,KnowledgeDocumentVersion version,
            List<KnowledgeChunk> chunks ) {
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null) {
            throw new BusinessException(
                    ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED,
                    "VectorStore 未启用，无法写入向量库"
            );
        }
        String jobId = UUID.randomUUID().toString();
        List<Document> vectorDocuments = new ArrayList<>();

        for (KnowledgeChunk chunk : chunks) {
            Document vectorDocument = new Document(chunk.getContent());

            // 这些 metadata 是后续检索过滤、引用溯源、删除向量的关键。
            vectorDocument.getMetadata().put("document_id", document.getId().toString());
            vectorDocument.getMetadata().put("version_id", version.getId().toString());
            vectorDocument.getMetadata().put("chunk_id", chunk.getId().toString());
            vectorDocument.getMetadata().put("chunk_index", chunk.getChunkIndex().toString());
            vectorDocument.getMetadata().put("category_id", document.getCategoryId() == null ? "" : document.getCategoryId().toString());
            vectorDocument.getMetadata().put("document_code", nullToEmpty(document.getDocumentCode()));
            vectorDocument.getMetadata().put("document_title", nullToEmpty(document.getTitle()));
            vectorDocument.getMetadata().put("source", nullToEmpty(version.getOriginalFilename()));
            vectorDocument.getMetadata().put("kb_vector_job_id", jobId);
            vectorDocuments.add(vectorDocument);
        }
        try {
            for (int start = 0; start < vectorDocuments.size(); start += MAX_BATCH_SIZE) {
                int end = Math.min(start + MAX_BATCH_SIZE, vectorDocuments.size());
                vectorStore.add(vectorDocuments.subList(start, end));
            }
        } catch (Exception e) {
            vectorRepository.deleteByVectorJobId(jobId);
            throw new BusinessException(
                    ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED,
                    "写入向量库失败: " + e.getMessage(),
                    e
            );
        }
    }
    /**
     * 标记完成
     * @param document
     * @param version
     * @param chunkCount
     */
    private void markCompleted(
            KnowledgeDocument document,
            KnowledgeDocumentVersion version,
            int chunkCount
    ) {
        version.setParseStatus("COMPLETED");
        version.setVectorStatus("COMPLETED");
        version.setVectorError(null);
        version.setChunkCount(chunkCount);
        version.setUpdatedAt(LocalDateTime.now());
        this.updateById(version);

        // 第一版策略：向量化成功后，把该版本设置为当前版本。
        // 后续如果你要做“审核后发布”，这里可以改成发布接口里再更新 currentVersionId。
        document.setCurrentVersionId(version.getId());
        document.setUpdatedAt(LocalDateTime.now());
        documentService.updateById(document);
    }
    /**
     * 标记失败
     * @param version
     * @param e
     */
    private void markFailed(KnowledgeDocumentVersion version, Exception e) {
        version.setParseStatus("FAILED");
        version.setVectorStatus("FAILED");
        version.setVectorError(truncateError(e.getMessage()));
        version.setUpdatedAt(LocalDateTime.now());
        this.updateById(version);

        log.error("文档版本向量化失败: versionId={}, error={}",
                version.getId(), e.getMessage(), e);
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
