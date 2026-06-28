package org.example.airag.modules.knowledgebase.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.airag.common.exception.BusinessException;
import org.example.airag.common.exception.ErrorCode;
import org.example.airag.common.file.ContentTypeDetectionService;
import org.example.airag.common.file.DocumentParseService;
import org.example.airag.common.file.FileHashService;
import org.example.airag.common.file.FileValidationService;
import org.example.airag.common.file.LocalFileStorageService;
import org.example.airag.modules.knowledgebase.entity.KnowledgeBase;
import org.example.airag.modules.knowledgebase.model.VectorStatus;
import org.example.airag.modules.knowledgebase.service.KnowledgeBaseService;
import org.example.airag.modules.knowledgebase.service.KnowledgeBaseUploadService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 知识库上传服务实现。
 *
 * <p>当前阶段采用同步流程：文件校验、文本解析、本地存储、元数据入库、向量化入库。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseUploadServiceImpl implements KnowledgeBaseUploadService {

    private static final long MAX_FILE_SIZE = 50 * 1024 * 1024;

    private final KnowledgeBaseService knowledgeBaseService;
    private final FileValidationService fileValidationService;
    private final ContentTypeDetectionService contentTypeDetectionService;
    private final FileHashService fileHashService;
    private final DocumentParseService documentParseService;
    private final LocalFileStorageService localFileStorageService;
    private final ObjectProvider<KnowledgeBaseVectorService> vectorServiceProvider;

    /**
     * 上传知识库文件，并在元数据保存成功后同步执行向量化。
     */
    @Override
    public Map<String, Object> uploadKnowledgeBase(MultipartFile file, String name, String category) {
        // 1. 校验文件大小、空文件。
        fileValidationService.validateFile(file, MAX_FILE_SIZE, "知识库");

        // 2. 检测真实 MIME 类型，避免只相信浏览器上传的 Content-Type。
        String contentType = contentTypeDetectionService.detectContentType(file);

        // 3. 校验文件类型；Markdown 允许通过扩展名兜底。
        fileValidationService.validateContentType(
                contentType,
                file.getOriginalFilename(),
                fileValidationService::isKnowledgeBaseMimeType,
                fileValidationService::isMarkdownExtension,
                "不支持的文件类型，支持 PDF、DOC、DOCX、TXT、MD、RTF"
        );

        // 4. 计算 hash，用于同文件去重。
        String fileHash = fileHashService.calculateHash(file);

        // 5. 查重；重复上传直接返回已有记录，避免重复写文件和重复向量化。
        KnowledgeBase existing = knowledgeBaseService.lambdaQuery()
                .eq(KnowledgeBase::getFileHash, fileHash)
                .eq(KnowledgeBase::getDelFlag, 0)
                .one();
        if (existing != null) {
            return Map.of("duplicate", true, "knowledgeBase", existing);
        }

        // 6. 解析文本，先确保文件不是“空内容”。
        String content = documentParseService.parseContent(file);
        if (content == null || content.isBlank()) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED, "无法从文件中提取文本内容");
        }

        // 7. 保存原始文件到本地，MySQL 中只存相对路径。
        String storagePath = localFileStorageService.saveKnowledgeBase(file);

        KnowledgeBase kb = buildKnowledgeBase(file, name, category, contentType, fileHash, storagePath);
        try {
            knowledgeBaseService.save(kb);
        } catch (Exception e) {
            // 元数据入库失败时清理已经落盘的本地文件，避免形成无主文件。
            localFileStorageService.deleteFile(storagePath);
            throw e;
        }

        log.info("保存知识库元数据成功: kbId={}, name={}", kb.getId(), kb.getName());

        // 8. 同步执行向量化，方法内部会维护 PROCESSING/COMPLETED/FAILED 状态。
        vectorizeKnowledgeBase(kb, content);

        return Map.of(
                "duplicate", false,
                "knowledgeBase", kb,
                "contentLength", content.length()
        );
    }

    @Override
    public void revectorize(Long id) {
        KnowledgeBase kb = getActiveKnowledgeBase(id);
        log.info("开始重新向量化知识库: kbId={}, name={}", kb.getId(), kb.getName());

        try {
            // 重新向量化必须基于原始文件重新解析，避免复用已经过期的文本内容。
            String content = parseStoredKnowledgeBase(kb);
            vectorizeKnowledgeBase(kb, content);
        } catch (BusinessException e) {
            // 文件丢失或解析失败时也要落库状态，方便列表页直接看到失败原因。
            markVectorFailed(kb, e.getMessage());
            throw e;
        } catch (Exception e) {
            markVectorFailed(kb, e.getMessage());
            throw new BusinessException(
                    ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED,
                    "重新向量化失败: " + e.getMessage(),
                    e
            );
        }
    }

    /**
     * 查询未删除的知识库记录。
     */
    private KnowledgeBase getActiveKnowledgeBase(Long id) {
        if (id == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "知识库ID不能为空");
        }

        KnowledgeBase kb = knowledgeBaseService.lambdaQuery()
                .eq(KnowledgeBase::getId, id)
                .eq(KnowledgeBase::getDelFlag, 0)
                .one();
        if (kb == null) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在");
        }
        return kb;
    }

    /**
     * 从本地存储读取原文件并重新解析文本内容。
     */
    private String parseStoredKnowledgeBase(KnowledgeBase kb) {
        byte[] fileBytes = localFileStorageService.downloadFile(kb.getStoragePath());
        String content = documentParseService.parseContent(fileBytes, kb.getOriginalFilename());
        if (content == null || content.isBlank()) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED, "无法从知识库原文件中提取文本内容");
        }
        return content;
    }

    /**
     * 构造知识库元数据实体，初始向量状态统一为 PENDING。
     */
    private KnowledgeBase buildKnowledgeBase(MultipartFile file, String name, String category,
                                             String contentType, String fileHash, String storagePath) {
        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(resolveName(name, file.getOriginalFilename()));
        kb.setCategory(resolveCategory(category));
        kb.setOriginalFilename(file.getOriginalFilename());
        kb.setContentType(contentType);
        kb.setFileSize(file.getSize());
        kb.setFileHash(fileHash);
        kb.setStoragePath(storagePath);
        kb.setVectorStatus(VectorStatus.PENDING.name());
        kb.setChunkCount(0);
        kb.setDelFlag(0);
        kb.setCreatedAt(LocalDateTime.now());
        kb.setUpdatedAt(LocalDateTime.now());
        return kb;
    }

    /**
     * 解析知识库名称：优先使用用户输入，否则从文件名去掉扩展名。
     */
    private String resolveName(String name, String filename) {
        if (name != null && !name.isBlank()) {
            return name.trim();
        }
        if (filename == null || filename.isBlank()) {
            return "未命名知识库";
        }
        int dotIndex = filename.lastIndexOf('.');
        return dotIndex > 0 ? filename.substring(0, dotIndex) : filename;
    }

    /**
     * 规范化分类字段，空字符串按未分类处理。
     */
    private String resolveCategory(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        return category.trim();
    }

    /**
     * 同步向量化知识库，并更新向量化状态。
     */
    private void vectorizeKnowledgeBase(KnowledgeBase kb, String content) {
        KnowledgeBaseVectorService vectorService = vectorServiceProvider.getIfAvailable();

        if (vectorService == null) {
            // 正式入库流程不能停留在 PENDING；没有 VectorStore 时明确标记失败，方便页面和排查。
            kb.setVectorStatus(VectorStatus.FAILED.name());
            kb.setVectorError("VectorStore 未启用，无法执行向量化");
            kb.setUpdatedAt(LocalDateTime.now());
            knowledgeBaseService.updateById(kb);
            log.warn("当前环境没有 VectorStore，已标记向量化失败: kbId={}", kb.getId());
            return;
        }

        try {
            kb.setVectorStatus(VectorStatus.PROCESSING.name());
            kb.setVectorError(null);
            kb.setChunkCount(0);
            kb.setUpdatedAt(LocalDateTime.now());
            knowledgeBaseService.updateById(kb);

            int chunkCount = vectorService.vectorizeAndStore(
                    kb.getId(),
                    content,
                    kb.getOriginalFilename()
            );

            kb.setVectorStatus(VectorStatus.COMPLETED.name());
            kb.setChunkCount(chunkCount);
            kb.setVectorError(null);
            kb.setUpdatedAt(LocalDateTime.now());
            knowledgeBaseService.updateById(kb);
        } catch (Exception e) {
            log.error("知识库向量化失败: kbId={}, error={}", kb.getId(), e.getMessage(), e);
            kb.setVectorStatus(VectorStatus.FAILED.name());
            kb.setVectorError(truncateError(e.getMessage()));
            kb.setUpdatedAt(LocalDateTime.now());
            knowledgeBaseService.updateById(kb);
        }
    }

    /**
     * 标记向量化失败状态。
     */
    private void markVectorFailed(KnowledgeBase kb, String message) {
        kb.setVectorStatus(VectorStatus.FAILED.name());
        kb.setVectorError(truncateError(message));
        kb.setUpdatedAt(LocalDateTime.now());
        knowledgeBaseService.updateById(kb);
    }

    /**
     * 限制错误信息长度，避免超过数据库字段长度。
     */
    private String truncateError(String message) {
        if (message == null) {
            return "未知错误";
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

}