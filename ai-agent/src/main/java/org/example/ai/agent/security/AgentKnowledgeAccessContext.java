package org.example.ai.agent.security;

import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.sso.AgentSessionService;
import org.example.ai.agent.sso.model.AgentSession;
import org.example.ai.agent.modules.knowledgebase.security.KnowledgeAccessContext;
import org.example.ai.agent.modules.knowledgebase.security.KnowledgeAccessPrincipal;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 使用Agent服务端会话为知识库提供可信身份。
 */
@Component
@RequiredArgsConstructor
public class AgentKnowledgeAccessContext implements KnowledgeAccessContext {

    private final CurrentUserProvider currentUserProvider;
    private final AgentSessionService agentSessionService;

    @Override
    public KnowledgeAccessPrincipal getRequiredPrincipal() {
        KnowledgeAccessPrincipal principal = getCurrentPrincipal();

        if (principal == null) {
            throw new BusinessException(
                    401,
                    "知识库查询需要有效的Agent登录会话"
            );
        }

        return principal;
    }

    @Override
    public KnowledgeAccessPrincipal getCurrentPrincipal() {
        /*
         * 先请求PM用户信息，确认当前Token和用户状态仍然有效。
         */
        String userId = currentUserProvider.getRequiredUserId();

        /*
         * 租户和部门信息只能读取服务端Redis会话，
         * 不接受浏览器请求参数提供的身份字段。
         */
        AgentSession session = agentSessionService.getCurrentSession();

        if (session == null) {
            return null;
        }

        if (!StringUtils.hasText(session.getUsername())
                || !session.getUsername().trim().equals(userId)) {
            throw new BusinessException(
                    401,
                    "Agent会话身份与当前PM用户不一致"
            );
        }

        if (session.getTenantId() == null) {
            throw new BusinessException(502,"PM登录身份缺少有效的租户ID");
        }

        return new KnowledgeAccessPrincipal(
                userId,
                session.getTenantId(),
                session.getDeptId()
        );
    }
}
