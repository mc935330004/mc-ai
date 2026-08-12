package org.example.ai.agent.modules.knowledgebase.service.impl;

import cn.hutool.core.util.ObjectUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.common.exception.ErrorCode;
import org.example.ai.agent.modules.knowledgebase.dto.KnowledgeDocumentDTO;
import org.example.ai.agent.modules.knowledgebase.dto.KnowledgeDocumentOverviewDTO;
import org.example.ai.agent.modules.knowledgebase.entity.KnowledgeBaseVectorTask;
import org.example.ai.agent.modules.knowledgebase.entity.KnowledgeChunk;
import org.example.ai.agent.modules.knowledgebase.entity.KnowledgeDocument;
import org.example.ai.agent.modules.knowledgebase.entity.KnowledgeDocumentVersion;
import org.example.ai.agent.modules.knowledgebase.mapper.KnowledgeDocumentMapper;
import org.example.ai.agent.modules.knowledgebase.service.KnowledgeBaseVectorTaskService;
import org.example.ai.agent.modules.knowledgebase.service.KnowledgeChunkService;
import org.example.ai.agent.modules.knowledgebase.service.KnowledgeDocumentService;
import org.example.ai.agent.modules.knowledgebase.service.KnowledgeDocumentVersionService;
import org.example.ai.agent.modules.knowledgebase.vo.KnowledgeDocumentListItemVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.example.ai.agent.modules.knowledgebase.security.KnowledgeAccessContext;
import org.example.ai.agent.modules.knowledgebase.security.KnowledgeAccessPrincipal;
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
    private final KnowledgeAccessContext knowledgeAccessContext;

    @Override
    public KnowledgeDocumentOverviewDTO overview(Long id) {
        KnowledgeAccessPrincipal principal =knowledgeAccessContext.getRequiredPrincipal();

        KnowledgeDocument document = getTenantDocument(id,principal.tenantId());

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
    public Page<KnowledgeDocumentListItemVO> findPageList(Page<KnowledgeDocumentListItemVO> page,KnowledgeDocumentDTO query) {
        KnowledgeAccessPrincipal principal =knowledgeAccessContext.getRequiredPrincipal();
        return baseMapper.findPageList(page, query, principal.tenantId());
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
        KnowledgeAccessPrincipal principal =
                knowledgeAccessContext.getRequiredPrincipal();

        KnowledgeDocument document = getTenantDocument(
                documentId,
                principal.tenantId()
        );

        if (document.getCurrentVersionId() == null) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "文档没有当前版本，不能恢复发布"
            );
        }

        KnowledgeDocumentVersion currentVersion =
                Optional.ofNullable(
                        versionService.lambdaQuery()
                                .eq(KnowledgeDocumentVersion::getId, document.getCurrentVersionId()
                                )
                                .eq(KnowledgeDocumentVersion::getDocumentId, document.getId()
                                )
                                .eq(KnowledgeDocumentVersion::getDelFlag, 0
                                )
                                .one()
                ).orElseThrow(() -> new BusinessException(
                        ErrorCode.BAD_REQUEST,
                        "当前文档版本不存在，不能恢复发布"
                ));

        if (!"COMPLETED".equals(currentVersion.getParseStatus())|| !"COMPLETED".equals(currentVersion.getVectorStatus())) {

            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "当前版本未完成解析和向量化，不能恢复发布"
            );
        }

        document.setStatus("PUBLISHED");
        document.setUpdatedAt(LocalDateTime.now());
        updateById(document);
    }

    @Override
    public Page<KnowledgeBaseVectorTask> findVectorTaskList(
            Page<KnowledgeBaseVectorTask> page,
            KnowledgeDocumentDTO query) {
        KnowledgeAccessPrincipal principal = knowledgeAccessContext.getRequiredPrincipal();
        return baseMapper.findVectorTaskList(page, query, principal.tenantId());
    }

    /**
     * 更新当前租户内的文档状态。
     */
    private void updateDocumentStatus(
            Long documentId,
            String status) {

        KnowledgeAccessPrincipal principal =knowledgeAccessContext.getRequiredPrincipal();
        KnowledgeDocument document = getTenantDocument(
                documentId,
                principal.tenantId()
        );
        document.setStatus(status);
        document.setUpdatedAt(LocalDateTime.now());
        updateById(document);
    }

    /**
     * 查询当前租户内的文档。
     *
     * 无权访问其他租户文档时统一返回文档不存在，
     * 避免通过错误信息探测其他租户的文档ID。
     */
    private KnowledgeDocument getTenantDocument(Long documentId,Long tenantId) {
        if (documentId == null || documentId <= 0) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "文档ID必须大于0"
            );
        }
        return Optional.ofNullable(
                lambdaQuery().eq(KnowledgeDocument::getId, documentId)
                        .eq(KnowledgeDocument::getTenantId, tenantId)
                        .eq(KnowledgeDocument::getDelFlag, 0)
                        .one()
        ).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,"文档不存在"));
    }
}
