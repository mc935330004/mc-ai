package org.example.ai.agent.security;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.security.CurrentUserProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 开发环境当前用户实现。
 *
 * 用户身份由可信业务网关通过 X-User-Id 请求头传递。
 */
@Component
@Profile("dev")
@RequiredArgsConstructor
public class HeaderCurrentUserProvider implements CurrentUserProvider {

    private static final String USER_ID_CODE = "PM-User-code";
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private final HttpServletRequest request;

    @Override
    public String getRequiredUserId() {
        String userId = request.getHeader(USER_ID_CODE);
        if (!StringUtils.hasText(userId)) {
            throw new BusinessException( 401,"请求头缺少当前用户身份：" + USER_ID_CODE );
        }

        return userId.trim();
    }

    @Override
    public String getRequiredAuthorization() {
        String authorization = request.getHeader(AUTHORIZATION_HEADER);
        if (!StringUtils.hasText(authorization)) {
            throw new BusinessException(401, "当前请求缺少 Authorization");
        }
        // 原样返回，例如：Bearer xxxxx，不在这里重复拼接 Bearer
        return authorization.trim();
    }
}