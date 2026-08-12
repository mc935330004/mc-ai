package org.example.ai.agent.modules.KnowledgeLog.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.common.exception.ErrorCode;
import org.example.ai.agent.modules.KnowledgeLog.entity.KnowledgeQueryReference;
import org.example.ai.agent.modules.KnowledgeLog.mapper.KnowledgeQueryReferenceMapper;
import org.example.ai.agent.modules.KnowledgeLog.service.KnowledgeQueryReferenceService;
import org.example.ai.agent.modules.knowledgebase.security.KnowledgeAccessContext;
import org.example.ai.agent.modules.knowledgebase.security.KnowledgeAccessPrincipal;
import org.springframework.stereotype.Service;

/**
 * 知识问答引用记录服务。
 */
@Service
@RequiredArgsConstructor
public class KnowledgeQueryReferenceServiceImpl extends ServiceImpl<KnowledgeQueryReferenceMapper,KnowledgeQueryReference>
        implements KnowledgeQueryReferenceService {
    private final KnowledgeAccessContext knowledgeAccessContext;

    @Override
    public Page<KnowledgeQueryReference> getReferences(Page<KnowledgeQueryReference> page, Long logId) {

        if (logId == null || logId <= 0) {
            throw new BusinessException(
                    ErrorCode.BAD_REQUEST,
                    "问答日志ID必须大于0"
            );
        }
        KnowledgeAccessPrincipal principal = knowledgeAccessContext.getRequiredPrincipal();
        return baseMapper.getReferences(page, logId, principal.tenantId());
    }
}