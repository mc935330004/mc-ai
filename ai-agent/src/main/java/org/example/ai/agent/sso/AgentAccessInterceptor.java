package org.example.ai.agent.sso;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.security.CurrentUserProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;

/**
 * Agent接口访问边界。
 *
 * 负责：
 * 1. 校验浏览器请求来源；
 * 2. 校验Agent登录会话；
 * 3. 隔离普通聊天用户和管理用户。
 */
@Component
@RequiredArgsConstructor
public class AgentAccessInterceptor
        implements org.springframework.web.servlet.HandlerInterceptor {

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private final CurrentUserProvider currentUserProvider;
    private final AgentSsoProperties properties;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,Object handler) {
        /*
         * 浏览器预检请求交给Web容器处理。
         */
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        /*
         * SSO没有启用时保持原有系统行为。
         */
        if (!properties.isEnabled()) {
            return true;
        }

        /*
         * Cookie认证模式需要防止第三方网站伪造写请求。
         */
        validateBrowserOrigin(request);

        String path = request.getRequestURI()
                .substring(request.getContextPath().length());

        /*
         * Ticket交换、当前用户、退出接口由Controller自行校验。
         *
         * 不能在这里提前要求Agent会话，
         * 因为首次Ticket交换时会话尚未创建。
         */
        if (PATH_MATCHER.match("/api/agent/auth/**", path)) {
            return true;
        }

        /*
         * 所有Agent业务接口首先验证PM用户登录状态。
         *
         * HeaderCurrentUserProvider会实时请求PM /user/info，
         * PM Token失效时立即删除Agent Redis会话。
         */
        currentUserProvider.getRequiredUserId();

        if (isChatUserPath(path)) {
            return true;
        }

        /*
         * 非聊天接口全部属于Agent管理端，
         * 必须具有PM按钮权限ai_agent_admin。
         */
        currentUserProvider.requirePermission(
                AgentSsoConstants.ADMIN_PERMISSION
        );

        return true;
    }

    /**
     * 校验Cookie认证下的浏览器写请求来源。
     */
    private void validateBrowserOrigin(
            HttpServletRequest request) {

        if (isSafeMethod(request.getMethod())) {
            return;
        }

        String origin = request.getHeader("Origin");

        /*
         * Postman、服务间调用可能没有Origin。
         * 浏览器跨站POST会自动携带Origin，前端不能伪造该请求头。
         */
        if (!StringUtils.hasText(origin)) {
            return;
        }

        if ("null".equalsIgnoreCase(origin)) {
            throw new BusinessException(
                    403,
                    "不允许来源不明的浏览器请求"
            );
        }

        boolean allowed = properties
                .getAllowedBrowserOrigins()
                .stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .anyMatch(origin::equals);

        if (!allowed) {
            throw new BusinessException(
                    403,
                    "当前浏览器来源无权访问Agent接口"
            );
        }
    }

    private boolean isSafeMethod(String method) {
        return "GET".equalsIgnoreCase(method)
                || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method);
    }

    /**
     * 判断当前接口是否允许普通已登录用户访问。
     *
     * 普通用户只允许使用聊天、待确认操作、动态表单以及知识库问答接口。
     * 知识库文档、分类、版本、切片和查询日志管理接口继续要求管理员权限。
     */
    private boolean isChatUserPath(String path) {
        return PATH_MATCHER.match("/api/agent/chat/**",path)
                || PATH_MATCHER.match("/api/agent/actions/**",path )
                || PATH_MATCHER.match("/api/agent/capabilities/*/fields/**",path)
                || "/api/knowledge/documents/query".equals(path)
                || "/api/knowledge/documents/query/stream".equals(path);
    }
}