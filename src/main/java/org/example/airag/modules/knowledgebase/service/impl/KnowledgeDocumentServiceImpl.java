package org.example.airag.modules.knowledgebase.service.impl;

import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.airag.common.exception.BusinessException;
import org.example.airag.common.exception.ErrorCode;
import org.example.airag.modules.knowledgebase.dto.KnowledgeDocumentDTO;
import org.example.airag.modules.knowledgebase.dto.KnowledgeDocumentOverviewDTO;
import org.example.airag.modules.knowledgebase.entity.KnowledgeBaseVectorTask;
import org.example.airag.modules.knowledgebase.entity.KnowledgeChunk;
import org.example.airag.modules.knowledgebase.entity.KnowledgeDocument;
import org.example.airag.modules.knowledgebase.entity.KnowledgeDocumentVersion;
import org.example.airag.modules.knowledgebase.mapper.KnowledgeDocumentMapper;
import org.example.airag.modules.knowledgebase.service.KnowledgeBaseVectorTaskService;
import org.example.airag.modules.knowledgebase.service.KnowledgeChunkService;
import org.example.airag.modules.knowledgebase.service.KnowledgeDocumentService;
import org.example.airag.modules.knowledgebase.service.KnowledgeDocumentVersionService;
import org.example.airag.modules.knowledgebase.vo.KnowledgeDocumentListItemVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor(onConstructor_ = {@Lazy, @Autowired})
public class KnowledgeDocumentServiceImpl extends ServiceImpl<KnowledgeDocumentMapper, KnowledgeDocument>
        implements KnowledgeDocumentService {
    private final KnowledgeDocumentVersionService versionService;
    private final KnowledgeChunkService chunkService;
    private final KnowledgeBaseVectorTaskService vectorTaskService;

    @Override
    public KnowledgeDocumentOverviewDTO overview(Long id) {
        KnowledgeDocument document = Optional.ofNullable(this.lambdaQuery()
                .eq(KnowledgeDocument::getId, id)
                .eq(KnowledgeDocument::getDelFlag, 0)
                .one()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "文档不存在"));

        KnowledgeDocumentVersion currentVersion = null;
        if (document.getCurrentVersionId() != null) {
            currentVersion = versionService.getById(document.getCurrentVersionId());
        }

        List<KnowledgeDocumentVersion> versions = versionService.lambdaQuery()
                .eq(KnowledgeDocumentVersion::getDocumentId, id)
                .eq(KnowledgeDocumentVersion::getDelFlag, 0)
                .orderByDesc(KnowledgeDocumentVersion::getCreatedAt)
                .list();
        // 统计文档的总分片数
        Long totalChunkCount = 0L;
        // 当前版本启用切片数。
        Long enabledChunkCount = 0L;
        // 当前版本禁用切片数。
        Long disabledChunkCount = 0L;

        //获取切片集合
        List<KnowledgeChunk> chunkList = chunkService.lambdaQuery()
                .eq(KnowledgeChunk::getVersionId, document.getCurrentVersionId())
                .eq(KnowledgeChunk::getDelFlag, 0)
                .list();
        if (ObjectUtil.isNotEmpty(chunkList) && !chunkList.isEmpty()) {
            totalChunkCount = (long) chunkList.size();

            enabledChunkCount = chunkList.stream().filter(chunk -> chunk.getEnabled() == 1).count();

            disabledChunkCount = chunkList.stream().filter(chunk -> chunk.getEnabled() == 0).count();
        }
        //最近一次向量化任务。
        KnowledgeBaseVectorTask latestVectorTask = vectorTaskService.lambdaQuery()
                .eq(KnowledgeBaseVectorTask::getDocumentId, id)
                .orderByDesc(KnowledgeBaseVectorTask::getCreatedAt)
                .last("LIMIT 1")
                .one();
        // 构建文档概览DTO
        KnowledgeDocumentOverviewDTO overview = new KnowledgeDocumentOverviewDTO();
        overview.setDocument(document);
        overview.setCurrentVersion(currentVersion);
        overview.setVersions(versions);
        overview.setTotalChunkCount(totalChunkCount);
        overview.setEnabledChunkCount(enabledChunkCount);
        overview.setDisabledChunkCount(disabledChunkCount);
        overview.setLatestVectorTask(latestVectorTask);
        overview.setChunkList(chunkList);
        return overview;
    }

    @Override
    public Page<KnowledgeDocumentListItemVO> findPageList(Page<KnowledgeDocumentListItemVO> page, KnowledgeDocumentDTO query) {
        return baseMapper.findPageList(page, query);
    }


    @Override
    public void deprecatedDocument(Long documentId) {
        updateDocumentStatus(documentId, "DEPRECATED");
    }

    @Override
    public void archiveDocument(Long documentId) {
        updateDocumentStatus(documentId, "ARCHIVED");

    }

    @Override
    public void restorePublished(Long documentId) {
        if (documentId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文档ID不能为空");
        }
        KnowledgeDocument document = Optional.ofNullable(lambdaQuery()
                .eq(KnowledgeDocument::getId, documentId)
                .eq(KnowledgeDocument::getDelFlag, 0)
                .one()).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "文档不存在"));
        if (document.getCurrentVersionId() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文档没有当前版本，不能恢复发布");
        }
        KnowledgeDocumentVersion currentVersion = Optional.ofNullable(versionService.lambdaQuery()
                .eq(KnowledgeDocumentVersion::getId,document.getCurrentVersionId())
                .eq(KnowledgeDocumentVersion::getDelFlag, 0).one())
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "当前版本不存在，不能恢复发布"));

        if (!"COMPLETED".equals(currentVersion.getParseStatus())
                || !"COMPLETED".equals(currentVersion.getVectorStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "当前版本未完成解析和向量化，不能恢复发布");
        }
        document.setStatus("PUBLISHED");
        document.setUpdatedAt(LocalDateTime.now());
        updateById(document);
    }

    @Override
    public Page<KnowledgeBaseVectorTask> findVectorTaskList(Page<KnowledgeBaseVectorTask> page, KnowledgeDocumentDTO query) {
        return baseMapper.findVectorTaskList(page, query);
    }

    /**
     * 更新文档状态。
     *
     * 文档状态不是 PUBLISHED 后，就不会被企业文档问答服务检索。
     * 这里不删除切片和向量，方便后续恢复、审计和历史追溯。
     */
    private void updateDocumentStatus(Long documentId, String status) {
        if (documentId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文档ID不能为空");
        }
        KnowledgeDocument document = lambdaQuery()
                .eq(KnowledgeDocument::getId, documentId)
                .eq(KnowledgeDocument::getDelFlag, 0)
                .one();
        if (document == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "文档不存在");
        }
        document.setStatus(status);
        document.setUpdatedAt(LocalDateTime.now());
        this.updateById(document);
    }
}
