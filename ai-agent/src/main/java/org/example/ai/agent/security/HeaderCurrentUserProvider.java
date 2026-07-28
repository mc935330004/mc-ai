package org.example.ai.agent.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.exception.BusinessException;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * PM 当前登录用户提供器。
 *
 * PM 使用不透明 Token，Agent 不自行解析 Token。
 * Agent 将 Authorization 发送到 PM 的 /user/info，
 * 并以 PM 返回的 username 作为唯一可信用户身份。
 */
@Component
@RequiredArgsConstructor
public class HeaderCurrentUserProvider implements CurrentUserProvider {

    /**
     * 当前请求已验证用户的缓存属性。
     *
     * 只保存在 HttpServletRequest 生命周期内，
     * 不会写入数据库、日志或跨用户共享。
     */
    private static final String VERIFIED_USER_ATTRIBUTE =HeaderCurrentUserProvider.class.getName() + ".verifiedUserId";

    private final HttpServletRequest request;
    /**
     * 手动解析PM响应，避免Spring Boot 4直接转换旧版JsonNode失败。
     */
    private final ObjectMapper objectMapper;
    /**
     * 复用项目已有 RestClient。
     *
     * baseUrl 已由 agent.business-api.base-url 配置，
     * 当前开发配置指向 http://pm.s-ic.cn/pm。
     */
    private final RestClient restClient;

    /**
     * 获取经过 PM 业务系统验证的当前用户编码。
     */
    @Override
    public String getRequiredUserId() {
        Object cached =
                request.getAttribute(
                        VERIFIED_USER_ATTRIBUTE
                );

        if (cached instanceof String userId
                && StringUtils.hasText(userId)) {
            return userId;
        }

        String authorization =
                getRequiredAuthorization();

        JsonNode response;

        try {
            String responseBody = restClient.get()
                    .uri("/user/info")
                    .header(
                            HttpHeaders.AUTHORIZATION,
                            authorization
                    )
                    .retrieve()
                    .body(String.class);

            if (!StringUtils.hasText(responseBody)) {
                throw new BusinessException(
                        401,
                        "业务系统没有返回当前用户信息"
                );
            }

            response = objectMapper.readTree(responseBody);
        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    502,
                    "业务系统用户信息返回格式不正确"
            );
        } catch (RestClientResponseException exception) {
            int status =
                    exception.getStatusCode().value();

            if (status == 401 || status == 403) {
                throw new BusinessException(
                        401,
                        "业务系统登录状态已失效或没有访问权限"
                );
            }

            throw new BusinessException(
                    502,
                    "业务系统用户身份校验失败"
            );

        } catch (RestClientException exception) {
            throw new BusinessException(
                    503,
                    "业务系统用户身份服务暂时不可用"
            );
        }

        String userId = response == null
                ? null
                : response.path("data")
                .path("sysUser")
                .path("username")
                .asText(null);

        if (!StringUtils.hasText(userId)) {
            throw new BusinessException(
                    401,
                    "业务系统未返回有效的当前用户身份"
            );
        }

        String verifiedUserId =
                userId.trim();

        // 只缓存经过 PM Token 校验后返回的用户编码。
        request.setAttribute(
                VERIFIED_USER_ATTRIBUTE,
                verifiedUserId
        );

        return verifiedUserId;
    }

    /**
     * 获取原始 PM 登录凭证。
     *
     * 不解析、不修改、不保存，也不重复添加 Bearer。
     */
    @Override
    public String getRequiredAuthorization() {
        String authorization =
                request.getHeader(
                        HttpHeaders.AUTHORIZATION
                );

        if (!StringUtils.hasText(authorization)) {
            throw new BusinessException(
                    401,
                    "当前请求缺少 Authorization"
            );
        }

        return authorization.trim();
    }
}