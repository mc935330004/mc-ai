package org.example.airag.modules.knowledgebase.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.airag.common.exception.BusinessException;
import org.example.airag.common.exception.ErrorCode;
import org.example.airag.common.file.LocalFileStorageService;
import org.example.airag.modules.knowledgebase.entity.KnowledgeBase;
import org.example.airag.modules.knowledgebase.service.KnowledgeBaseService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 知识库删除服务。
 *
 * <p>对应 interview-guide 的 KnowledgeBaseDeleteService。
 * 当前项目使用 delFlag 逻辑删除，避免误删后无法追踪。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeBaseDeleteService {

    private final KnowledgeBaseService knowledgeBaseService;
    private final LocalFileStorageService localFileStorageService;
    private final ObjectProvider<KnowledgeBaseVectorService> vectorServiceProvider;

    /**
     * 删除知识库。
     *
     * <p>删除顺序：逻辑删除 MySQL 记录 -> 删除向量 -> 删除本地文件。</p>
     */
    public void deleteKnowledgeBase(Long id) {
        KnowledgeBase kb = knowledgeBaseService.lambdaQuery()
                .eq(KnowledgeBase::getId, id)
                .eq(KnowledgeBase::getDelFlag, 0)
                .one();
        if (kb == null) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "知识库不存在");
        }

        // 1. 先逻辑删除，避免用户继续查询这个知识库。
        kb.setDelFlag(1);
        kb.setUpdatedAt(LocalDateTime.now());
        knowledgeBaseService.updateById(kb);

        // 2. 再删除 PGVector 里的向量数据。
        deleteVectors(id);

        // 3. 最后删除本地原始文件。
        deleteLocalFile(kb.getStoragePath());

        log.info("知识库已删除: id={}, name={}", kb.getId(), kb.getName());
    }

    /**
     * 删除向量数据；没有启用 VectorStore 时直接跳过。
     */
    private void deleteVectors(Long id) {
        KnowledgeBaseVectorService vectorService = vectorServiceProvider.getIfAvailable();
        if (vectorService == null) {
            log.warn("VectorStore 未启用，跳过向量删除: kbId={}", id);
            return;
        }
        vectorService.deleteByKnowledgeBaseId(id);
    }

    /**
     * 删除本地文件；失败时记录日志，不影响知识库删除结果。
     */
    private void deleteLocalFile(String storagePath) {
        try {
            localFileStorageService.deleteFile(storagePath);
        } catch (Exception e) {
            log.warn("知识库记录已删除，但本地文件清理失败，可后续手动补偿: storagePath={}, error={}",
                    storagePath, e.getMessage(), e);
        }
    }
}
