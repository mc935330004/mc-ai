package org.example.ai.agent.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.stability.ExternalServiceCircuitBreaker;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * PM接口permission解析代理。
 *
 * 安全原则：
 * 1. 浏览器不能直接访问PM；
 * 2. 只转发当前请求的Authorization；
 * 3. Token不写入数据库、缓存和日志；
 * 4. PM解析接口地址固定，用户输入不能控制出站目标；
 * 5. 只返回经过格式校验的permission。
 */
@Component
@RequiredArgsConstructor
public class PmPermissionResolver {

    /**
     * PM权限解析接口固定地址。
     *
     * 如果你PM实际接口地址不同，只修改这一处。
     */
    private static final String RESOLVE_ENDPOINT ="/ai/permission/resolve";
    private static final String SERVICE_NAME = "pm-permission-resolver";

    private static final Set<String> ALLOWED_METHODS =Set.of("GET","POST", "PUT","PATCH", "DELETE");

    private final RestClient restClient;
    private final ExternalServiceCircuitBreaker circuitBreaker;

    /**
     * 用于读取WRITE能力输入Schema中的权限配置。
     */
    private final ObjectMapper objectMapper;
    /**
     * 查询业务接口对应的PM permission。
     *
     * @param method        业务接口HTTP方法
     * @param path          业务接口相对路径
     * @param authorization 当前用户PM登录凭证
     */
    public String resolve(
            String method,
            String path,
            String authorization) {

        String normalizedMethod =normalizeMethod(method);
        String normalizedPath =normalizePath(path);
        if (!StringUtils.hasText(authorization)) {
            throw new BusinessException(401,"查询业务权限缺少登录凭证");
        }
        JsonNode response;

        circuitBreaker.beforeCall(SERVICE_NAME);
        try {
            String responseBody = restClient.post()
                    .uri(RESOLVE_ENDPOINT)
                    .header(HttpHeaders.AUTHORIZATION,authorization.trim() )
                    .body(Map.of("method",normalizedMethod,"path",normalizedPath))
                    .retrieve()
                    .body(String.class);

            if (!StringUtils.hasText(responseBody)) {
                circuitBreaker.recordReachable(SERVICE_NAME);
                throw new BusinessException(
                        502,
                        "PM权限解析接口返回内容为空"
                );
            }
            response = objectMapper.readTree(responseBody);
            circuitBreaker.recordSuccess(SERVICE_NAME);
        } catch (JsonProcessingException exception) {
            circuitBreaker.recordReachable(SERVICE_NAME);
            throw new BusinessException(
                    502,
                    "PM权限解析接口返回的不是合法JSON"
            );

        } catch (RestClientResponseException exception) {
            int status = exception
                    .getStatusCode()
                    .value();

            if (status >= 500) {
                circuitBreaker.recordFailure(SERVICE_NAME);
            } else {
                circuitBreaker.recordReachable(SERVICE_NAME);
            }

            if (status == 401 || status == 403) {
                throw new BusinessException(
                        403,
                        "当前用户没有查询接口权限配置的权限"
                );
            }

            if (status == 404) {
                throw new BusinessException(
                        404,
                        "PM没有配置当前接口对应的permission"
                );
            }

            throw new BusinessException(
                    502,
                    "PM权限解析接口调用失败"
            );

        } catch (RestClientException exception) {
            circuitBreaker.recordFailure(SERVICE_NAME);
            throw new BusinessException(
                    503,
                    "PM权限解析服务暂时不可用"
            );
        }
        return readPermission(response);
    }


    /**
     * 保存WRITE能力前验证Schema中的permission没有被伪造。
     *
     * 前端自动匹配只负责用户体验，最终安全判断必须在后端执行。
     */
    public void verifyWriteConfiguration(
            String method,
            String path,
            String inputSchemaJson,
            String authorization) {

        /*
         * 每次保存都重新查询PM权威映射，
         * 不相信前端提交的permission。
         */
        String expectedPermission =
                resolve(
                        method,
                        path,
                        authorization
                );

        if (!StringUtils.hasText(inputSchemaJson)) {
            throw new BusinessException(
                    400,
                    "WRITE能力请求Schema不能为空"
            );
        }

        try {
            JsonNode schema =
                    objectMapper.readTree(inputSchemaJson);

            if (schema == null || !schema.isObject()) {
                throw new BusinessException(
                        400,
                        "WRITE能力请求Schema必须是JSON对象"
                );
            }

            String configuredPermission =
                    schema.path("x-required-permission")
                            .asText(null);

            if (!StringUtils.hasText(configuredPermission)) {
                throw new BusinessException(
                        400,
                        "请先自动匹配业务接口permission"
                );
            }

            /*
             * 必须完全一致。
             * 只要PM映射发生变化，旧Schema就必须重新匹配。
             */
            if (!expectedPermission.equals(
                    configuredPermission.trim())) {

                throw new BusinessException(
                        400,
                        "业务接口permission已变化，请重新执行自动匹配"
                );
            }

        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    400,
                    "WRITE能力请求Schema不是合法JSON"
            );
        }
    }

    /**
     * 解析PM统一响应，只读取data.permission。
     */
    private String readPermission(JsonNode response) {
        if (response == null || !response.isObject()) {
            throw new BusinessException(
                    502,
                    "PM权限解析接口返回格式不正确"
            );
        }

        String code =
                response.path("code").asText("");

        /*
         * 兼容PM常见的code=0以及code=200成功格式。
         */
        if (!"0".equals(code) && !"200".equals(code)) {
            throw new BusinessException(
                    404,
                    "PM没有配置当前接口对应的permission"
            );
        }

        String permission =
                response.path("data")
                        .path("permission")
                        .asText(null);

        if (!StringUtils.hasText(permission)) {
            throw new BusinessException(
                    502,
                    "PM权限解析接口没有返回permission"
            );
        }

        String normalizedPermission =permission.trim();

        /*
         * permission只允许安全字符。
         * 禁止脚本、SpEL表达式和其他动态内容进入能力Schema。
         */
        if (!normalizedPermission.matches("[A-Za-z0-9:_.*-]{1,128}")) {
            throw new BusinessException( 502,"PM返回的permission格式不正确");
        }
        return normalizedPermission;
    }

    private String normalizeMethod(String method) {
        if (!StringUtils.hasText(method)) {
            throw new BusinessException(
                    400,
                    "HTTP请求方法不能为空"
            );
        }

        String normalized =
                method.trim().toUpperCase(Locale.ROOT);

        if (!ALLOWED_METHODS.contains(normalized)) {
            throw new BusinessException(
                    400,
                    "不支持的HTTP请求方法：" + normalized
            );
        }

        return normalized;
    }

    private String normalizePath(String path) {
        if (!StringUtils.hasText(path)) {
            throw new BusinessException(
                    400,
                    "接口相对路径不能为空"
            );
        }

        String normalized = path.trim();

        if (normalized.length() > 300
                || !normalized.startsWith("/")
                || normalized.contains("://")
                || normalized.contains("?")
                || normalized.contains("#")
                || normalized.contains("..")
                || normalized.contains("\\")) {

            throw new BusinessException(
                    400,
                    "接口地址必须是不包含域名、查询参数和路径穿越符的相对路径"
            );
        }

        return normalized;
    }
}
