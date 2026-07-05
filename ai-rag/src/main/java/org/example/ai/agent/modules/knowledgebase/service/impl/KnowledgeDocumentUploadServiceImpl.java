package org.example.ai.agent.modules.knowledgebase.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.common.file.ContentTypeDetectionService;
import org.example.ai.agent.common.file.FileHashService;
import org.example.ai.agent.common.file.FileValidationService;
import org.example.ai.agent.modules.knowledgebase.dto.KnowledgeDocumentUploadRequest;
import org.example.ai.agent.modules.knowledgebase.dto.KnowledgeDocumentUploadResponse;
import org.example.ai.agent.modules.knowledgebase.entity.KnowledgeDocument;
import org.example.ai.agent.modules.knowledgebase.entity.KnowledgeDocumentVersion;
import org.example.ai.agent.modules.knowledgebase.model.VectorStatus;
import org.example.ai.agent.modules.knowledgebase.service.*;
import org.example.ai.agent.modules.knowledgebase.service.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 知识库文档上传服务实现类
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class KnowledgeDocumentUploadServiceImpl implements KnowledgeDocumentUploadService {
    private static final long MAX_FILE_SIZE = 50 * 1024 * 1024;

    private final KnowledgeDocumentService documentService;
    private final KnowledgeDocumentVersionService versionService;
    private final KnowledgeBaseVectorTaskService vectorTaskService;
    private final FileStorageService fileStorageService;
    private final FileValidationService fileValidationService;
    private final ContentTypeDetectionService contentTypeDetectionService;
    private final FileHashService fileHashService;

    /**
     * 上传企业知识文档，并创建文档版本和向量化任务。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeDocumentUploadResponse upload(KnowledgeDocumentUploadRequest request) {
        var file = request.getFile();
        // 1. 校验文件大小、空文件等基础规则
        fileValidationService.validateFile(file, MAX_FILE_SIZE, "知识文档");

        // 2. 检测真实文件类型，避免只相信浏览器传来的 Content-Type
        String contentType = contentTypeDetectionService.detectContentType(file);
        // 3. 校验是否为知识库允许的文档类型
        fileValidationService.validateContentType(
                contentType,
                file.getOriginalFilename(),
                fileValidationService::isKnowledgeBaseMimeType,
                fileValidationService::isMarkdownExtension,
                "不支持的文件类型，支持 PDF、DOC、DOCX、TXT、MD、RTF"
        );
        // 4. 计算文件 Hash，用于后续版本去重和审计
        String fileHash = fileHashService.calculateHash(file);
        // 5. 保存原始文件，数据库只保存存储路径
        String storagePath = fileStorageService.saveKnowledgeBase(file);
        // 6. 创建文档主表
        KnowledgeDocument document = buildDocument(request);
        documentService.save(document);
        // 7. 创建文档版本表
        KnowledgeDocumentVersion version = buildVersion(request, document.getId(), contentType, fileHash, storagePath);
        versionService.save(version);
        // 8. 创建异步向量化任务
        Long taskId = vectorTaskService.createDocumentVersionVectorizeTask(document.getId(), version.getId());
        log.info("企业知识文档上传完成: documentId={}, versionId={}, taskId={}",
                document.getId(), version.getId(), taskId);
        return new KnowledgeDocumentUploadResponse(
                document.getId(),
                version.getId(),
                document.getTitle(),
                version.getVersionNo(),
                taskId,
                version.getParseStatus(),
                version.getVectorStatus()
        );
    }

    /**
     * 构建文档主表
     * @param request
     * @return
     */
    private KnowledgeDocument buildDocument(KnowledgeDocumentUploadRequest request) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setCategoryId(request.getCategoryId());
        document.setTitle(request.getTitle().trim());
        document.setDocumentCode(trimToNull(request.getDocumentCode()));
        document.setOwnerDept(trimToNull(request.getOwnerDept()));
        document.setStatus("DRAFT");
        document.setSummary(trimToNull(request.getSummary()));
        document.setCurrentVersionId(null);
        document.setDelFlag(0);
        document.setCreatedAt(LocalDateTime.now());
        document.setUpdatedAt(LocalDateTime.now());
        return document;
    }

    /**
     * 构建文档版本表
     * @param request
     * @param documentId
     * @param contentType
     * @param fileHash
     * @param storagePath
     * @return
     */
    private KnowledgeDocumentVersion buildVersion(
            KnowledgeDocumentUploadRequest request,
            Long documentId,
            String contentType,
            String fileHash,
            String storagePath
    ) {
        KnowledgeDocumentVersion version = new KnowledgeDocumentVersion();
        version.setDocumentId(documentId);
        version.setVersionNo(request.getVersionNo().trim());
        version.setOriginalFilename(request.getFile().getOriginalFilename());
        version.setContentType(contentType);
        version.setFileSize(request.getFile().getSize());
        version.setFileHash(fileHash);
        version.setStoragePath(storagePath);
        version.setParseStatus("PENDING");
        version.setVectorStatus(VectorStatus.PENDING.name());
        version.setVectorError(null);
        version.setChunkCount(0);
        version.setEffectiveStartTime(request.getEffectiveStartTime());
        version.setEffectiveEndTime(request.getEffectiveEndTime());
        version.setDelFlag(0);
        version.setCreatedAt(LocalDateTime.now());
        version.setUpdatedAt(LocalDateTime.now());
        return version;
    }
    /**
     * 去除空格
     * @param value
     * @return
     */
    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
