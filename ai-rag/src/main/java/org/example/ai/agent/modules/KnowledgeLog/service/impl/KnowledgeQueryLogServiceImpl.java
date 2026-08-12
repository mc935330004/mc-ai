package org.example.ai.agent.modules.KnowledgeLog.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.common.exception.ErrorCode;
import org.example.ai.agent.modules.KnowledgeLog.dto.KnowledgeQueryStatsDTO;
import org.example.ai.agent.modules.KnowledgeLog.entity.KnowledgeQueryLog;
import org.example.ai.agent.modules.KnowledgeLog.mapper.KnowledgeQueryLogMapper;
import org.example.ai.agent.modules.KnowledgeLog.service.KnowledgeQueryLogService;
import org.example.ai.agent.modules.knowledgebase.security.KnowledgeAccessContext;
import org.example.ai.agent.modules.knowledgebase.security.KnowledgeAccessPrincipal;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 知识问答日志服务。
 */
@Service
@RequiredArgsConstructor
public class KnowledgeQueryLogServiceImpl extends ServiceImpl<KnowledgeQueryLogMapper, KnowledgeQueryLog>
        implements KnowledgeQueryLogService {

    private final KnowledgeAccessContext knowledgeAccessContext;

    @Override
    public Page<KnowledgeQueryLog> findKnowledgeQueryLogList(Page<KnowledgeQueryLog> page, String status, String answer) {

        KnowledgeAccessPrincipal principal =knowledgeAccessContext.getRequiredPrincipal();
        return baseMapper.findKnowledgeQueryLogList(page, principal.tenantId(), status, answer);
    }

    @Override
    public KnowledgeQueryLog getKnowledgeQueryLogDetail(Long id) {
        if (id == null || id <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "问答日志ID必须大于0");
        }

        KnowledgeAccessPrincipal principal = knowledgeAccessContext.getRequiredPrincipal();

        /*
         * 其他租户日志统一返回不存在，
         * 避免通过错误信息探测日志ID。
         */
        return Optional.ofNullable(
                lambdaQuery()
                        .eq(KnowledgeQueryLog::getId, id)
                        .eq(KnowledgeQueryLog::getTenantId, principal.tenantId())
                        .one()
        ).orElseThrow(() -> new BusinessException(
                ErrorCode.NOT_FOUND,
                "问答日志不存在"
        ));
    }

    @Override
    public KnowledgeQueryStatsDTO
    getEnterpriseQuestionStatistics() {
        KnowledgeAccessPrincipal principal = knowledgeAccessContext.getRequiredPrincipal();
        return baseMapper.getEnterpriseQuestionStatistics(principal.tenantId());
    }
}