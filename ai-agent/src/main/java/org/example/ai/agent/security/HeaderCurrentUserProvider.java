package org.example.ai.agent.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.sso.AgentSessionService;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.HashSet;
import java.util.Set;

/**
 * PM当前登录用户和权限提供器。
 *
 * PM使用不透明Token，Agent不自行解析Token。
 * 当前请求只访问一次PM的/user/info，
 * 用户身份和权限只在HttpServletRequest生命周期内缓存。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HeaderCurrentUserProvider implements CurrentUserProvider {

    private static final String VERIFIED_CONTEXT_ATTRIBUTE =HeaderCurrentUserProvider.class.getName()+ ".verifiedContext";

    private final HttpServletRequest request;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final AgentSessionService agentSessionService;
    /**
     * 身份查询总尝试次数。
     *
     * 第一次请求可能遇到空闲连接被网关关闭，
     * GET 请求允许立即重试一次。
     */
    private static final int PM_USER_INFO_MAX_ATTEMPTS = 2;

    @Override
    public String getRequiredUserId() {
        return getRequiredContext() .userId();
    }

    @Override
    public void requirePermission(String permission ) {
        if (!StringUtils.hasText(permission)) {
            throw new IllegalArgumentException("权限编码不能为空");
        }
        if (!hasPermission(permission)) {
            throw new BusinessException(
                    403,
                    "当前用户没有操作权限"
            );
        }
    }

    @Override
    public boolean hasPermission(String permission) {
        if (!StringUtils.hasText(permission)) {
            return false;
        }
        return getRequiredContext()
                .permissions()
                .contains(permission.trim());
    }

    /**
     * 获取当前请求内已经验证的用户上下文。
     */
    private VerifiedUserContext
    getRequiredContext() {
        Object cached = request.getAttribute( VERIFIED_CONTEXT_ATTRIBUTE );
        if (cached instanceof VerifiedUserContext context) {
            return context;
        }
        VerifiedUserContext context =loadFromPm();
        /*
         * 只缓存用户编码和权限编码。
         * 不缓存Authorization、Cookie和Token。
         */
        request.setAttribute( VERIFIED_CONTEXT_ATTRIBUTE,context);
        return context;
    }

    /**
     * 从PM的/user/info读取权威用户信息。
     */
    private VerifiedUserContext loadFromPm() {
        String authorization = getRequiredAuthorization();
        JsonNode response;
        try {
            String responseBody =requestPmUserInfo(authorization);
            if (!StringUtils.hasText(responseBody)) {
                throw new BusinessException( 401,"业务系统没有返回当前用户信息");
            }
            response =objectMapper.readTree(responseBody);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    502,
                    "业务系统用户信息返回格式不正确"
            );

        } catch (RestClientResponseException exception) {
            int status =exception.getStatusCode().value();
            if (status == 401 || status == 403) {
                /*
                 * PM Token失效后立即清除Agent会话。
                 */
                agentSessionService.deleteCurrentSession();
                throw new BusinessException(401,"业务系统登录状态已失效或没有访问权限");
            }
            throw new BusinessException(502,"业务系统用户身份校验失败");

        } catch (RestClientException exception) {
            throw new BusinessException(503,"业务系统用户身份服务暂时不可用");
        }

        JsonNode data =response == null
                        ? null
                        : response.path("data");

        String userId =data == null ? null
                        : data.path("sysUser")
                        .path("username")
                        .asText(null);

        if (!StringUtils.hasText(userId)) {
            throw new BusinessException(
                    401,
                    "业务系统未返回有效的当前用户身份"
            );
        }
        Set<String> permissions =new HashSet<>();
        JsonNode permissionArray =data.path("permissions");
        if (permissionArray.isArray()) {
            for (JsonNode item : permissionArray) {
                String value = item.asText(null);
                if (StringUtils.hasText(value)) {
                    permissions.add(value.trim());
                }
            }
        }

        return new VerifiedUserContext(userId.trim(),Set.copyOf(permissions));
    }

    /**
     * 请求 PM 当前用户信息。
     *
     * 只重试网络传输异常。
     * 401、403、500 等明确 HTTP 响应不重试。
     */
    private String requestPmUserInfo(String authorization) {
        RestClientException lastException = null;
        for (int attempt = 1; attempt <= PM_USER_INFO_MAX_ATTEMPTS; attempt++) {
            try {
                return restClient.get()
                        .uri("/user/info")
                        .header(HttpHeaders.AUTHORIZATION,authorization)
                        .retrieve()
                        .body(String.class);

            } catch (RestClientResponseException exception) {
                /*
                 * PM 已经返回明确 HTTP 状态，
                 * 继续交给 loadFromPm() 按原规则处理。
                 */
                throw exception;
            } catch (RestClientException exception) {
                lastException = exception;

                /*
                 * 日志不记录 Authorization、Cookie 或 Token。
                 */
                log.warn(
                        "PM用户身份查询发生网络异常，attempt={}, errorType={}, message={}",
                        attempt,
                        exception.getClass().getSimpleName(),
                        exception.getMessage()
                );
            }
        }
        throw new RestClientException( "PM用户身份查询连续失败",lastException);
    }

    /**
     * 获取原始PM登录凭证。
     *
     * 不解析、不修改、不保存，也不重复添加Bearer。
     */
    @Override
    public String getRequiredAuthorization() {
        /*
         * 兼容原有接口调用方式：
         * 如果请求头已经携带Authorization，继续使用原有Token。
         */
        String authorization =request.getHeader(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(authorization)) {
            return authorization.trim();
        }
        /*
         * SSO方式：
         * 浏览器不再传PM Token，
         * 从Agent服务端Redis会话中恢复PM Token。
         */
        return agentSessionService.getRequiredAuthorization();
    }

    /**
     * 当前请求内可信的PM用户上下文。
     */
    private record VerifiedUserContext(String userId,Set<String> permissions ) {

    }
}