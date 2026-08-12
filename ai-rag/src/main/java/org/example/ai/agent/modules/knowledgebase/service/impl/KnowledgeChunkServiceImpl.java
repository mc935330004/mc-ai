package org.example.ai.agent.modules.knowledgebase.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.example.ai.agent.modules.knowledgebase.entity.KnowledgeChunk;
import org.example.ai.agent.modules.knowledgebase.mapper.KnowledgeChunkMapper;
import org.example.ai.agent.modules.knowledgebase.service.KnowledgeChunkService;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.common.exception.ErrorCode;
import org.example.ai.agent.modules.knowledgebase.entity.KnowledgeDocument;
import org.example.ai.agent.modules.knowledgebase.security.KnowledgeAccessContext;
import org.example.ai.agent.modules.knowledgebase.security.KnowledgeAccessPrincipal;
import org.example.ai.agent.modules.knowledgebase.service.KnowledgeDocumentService;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class KnowledgeChunkServiceImpl extends ServiceImpl<KnowledgeChunkMapper, KnowledgeChunk>
        implements KnowledgeChunkService {
    private final KnowledgeAccessContext knowledgeAccessContext;
    private final KnowledgeDocumentService documentService;

    @Override
    public Page<KnowledgeChunk> findChunksByDocumentVersionId(
            Page<KnowledgeChunk> page,
            String keyword) {
        KnowledgeAccessPrincipal principal = knowledgeAccessContext.getRequiredPrincipal();
        return baseMapper.findChunksByDocumentVersionId(
                page,
                keyword,
                principal.tenantId()
        );
    }

    @Override
    public void updateEnabled(Long id, Integer enabled) {
        if (id == null || id <= 0) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "切片ID必须大于0"
            );
        }

        if (enabled == null || (enabled != 0 && enabled != 1)) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "切片启用状态只能是0或1"
            );
        }

        KnowledgeAccessPrincipal principal =
                knowledgeAccessContext.getRequiredPrincipal();

        KnowledgeChunk chunk = Optional.ofNullable(
                lambdaQuery()
                        .eq(KnowledgeChunk::getId, id)
                        .eq(KnowledgeChunk::getDelFlag, 0)
                        .one()
        ).orElseThrow(() -> new BusinessException(
                ErrorCode.NOT_FOUND,
                "切片不存在"
        ));

        Long documentCount = documentService.lambdaQuery()
                .eq(KnowledgeDocument::getId, chunk.getDocumentId())
                .eq(KnowledgeDocument::getTenantId, principal.tenantId() )
                .eq(KnowledgeDocument::getDelFlag, 0)
                .count();
        if (documentCount == null || documentCount == 0) {
            throw new BusinessException(
                    ErrorCode.NOT_FOUND,
                    "切片不存在"
            );
        }

        chunk.setEnabled(enabled);
        chunk.setUpdatedAt(LocalDateTime.now());
        updateById(chunk);
    }
}
