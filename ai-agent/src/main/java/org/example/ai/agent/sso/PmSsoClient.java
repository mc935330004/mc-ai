package org.example.ai.agent.sso;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.sso.dto.PmSsoIdentity;
import org.example.ai.agent.stability.ExternalServiceCircuitBreaker;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/**
 * Agent调用PM Ticket交换接口。
 */
@Component
public class PmSsoClient {

    private static final String CLIENT_ID_HEADER = "X-Agent-Client-Id";
    private static final String CLIENT_SECRET_HEADER = "X-Agent-Client-Secret";
    private static final String SERVICE_NAME = "pm-sso";

    private final AgentSsoProperties properties;
    private final ObjectMapper objectMapper;
    private final ExternalServiceCircuitBreaker circuitBreaker;
    private final RestClient restClient;

    /**
     * 创建带有限超时的SSO专用客户端，不复用业务能力客户端。
     */
    public PmSsoClient(
            AgentSsoProperties properties,
            ObjectMapper objectMapper,
            ExternalServiceCircuitBreaker circuitBreaker) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.circuitBreaker = circuitBreaker;
        this.restClient = createRestClient(properties);
    }

    /**
     * 使用一次性Ticket交换PM可信身份。
     */
    public PmSsoIdentity exchange(String ticket) {
        checkConfiguration();
        circuitBreaker.beforeCall(SERVICE_NAME);
        try {
            String responseBody = restClient.post()
                    .uri(buildExchangeUrl())
                    .header(CLIENT_ID_HEADER, properties.getClientId())
                    .header(CLIENT_SECRET_HEADER, properties.getClientSecret())
                    .body(Map.of("ticket", ticket))
                    .retrieve()
                    .body(String.class);

            if (!StringUtils.hasText(responseBody)) {
                circuitBreaker.recordReachable(SERVICE_NAME);
                throw new BusinessException(
                        502,
                        "PM单点登录接口没有返回数据"
                );
            }

            circuitBreaker.recordSuccess(SERVICE_NAME);

            JsonNode root = objectMapper.readTree(responseBody);

            /*
             * PM统一响应成功码为0。
             */
            if (root.path("code").asInt(-1) != 0) {
                String message = root.path("msg").asText(
                        root.path("message").asText("PM单点登录失败")
                );
                throw new BusinessException(401, message);
            }

            JsonNode data = root.path("data");
            if (!data.isObject()) {
                throw new BusinessException(
                        502,
                        "PM单点登录身份数据格式不正确"
                );
            }

            PmSsoIdentity identity =objectMapper.treeToValue(data, PmSsoIdentity.class);

            validateIdentity(identity);
            return identity;

        } catch (BusinessException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();

            if (status == 401 || status == 403 || status == 410) {
                circuitBreaker.recordReachable(SERVICE_NAME);
                throw new BusinessException(
                        401,
                        "单点登录Ticket无效、已过期或没有访问权限"
                );
            }
            if (status >= 500) {
                circuitBreaker.recordFailure(SERVICE_NAME);
            } else {
                circuitBreaker.recordReachable(SERVICE_NAME);
            }
            throw new BusinessException(
                    502,
                    "PM单点登录服务返回异常"
            );
        } catch (JsonProcessingException exception) {
            circuitBreaker.recordReachable(SERVICE_NAME);
            throw new BusinessException(
                    502,
                    "PM单点登录返回格式不正确"
            );
        } catch (RestClientException exception) {
            circuitBreaker.recordFailure(SERVICE_NAME);
            throw new BusinessException(503,
                    "PM单点登录服务暂时不可用"
            );
        }
    }

    /**
     * 为Ticket交换接口设置有限连接和读取超时。
     */
    private RestClient createRestClient(
            AgentSsoProperties ssoProperties) {
        Duration connectTimeout = Duration.ofMillis(
                ssoProperties.getConnectTimeoutMs()
        );
        Duration readTimeout = Duration.ofMillis(
                ssoProperties.getReadTimeoutMs()
        );
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }

    private void validateIdentity(PmSsoIdentity identity) {
        if (identity == null
                || identity.getUserId() == null
                || !StringUtils.hasText(identity.getUsername())
                || !StringUtils.hasText(identity.getTarget())
                || !StringUtils.hasText(identity.getPmAccessToken())) {

            throw new BusinessException(
                    502,
                    "PM没有返回完整的用户身份"
            );
        }

        String target = identity.getTarget().trim().toUpperCase();

        if (!AgentSsoConstants.TARGET_CHAT.equals(target)
                && !AgentSsoConstants.TARGET_ADMIN.equals(target)) {
            throw new BusinessException(
                    502,
                    "PM返回了不支持的Agent入口类型"
            );
        }

        identity.setTarget(target);

        /*
         * Agent再次进行管理员权限校验。
         * 不能只相信PM返回的target=ADMIN。
         */
        if (AgentSsoConstants.TARGET_ADMIN.equals(target)
                && !identity.hasAgentAdminPermission()) {
            throw new BusinessException(
                    403,
                    "当前用户没有Agent管理权限"
            );
        }
    }

    private String buildExchangeUrl() {
        String baseUrl = properties.getPmBaseUrl().trim();
        String path = properties.getExchangePath().trim();

        if (baseUrl.endsWith("/") && path.startsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1) + path;
        }

        if (!baseUrl.endsWith("/") && !path.startsWith("/")) {
            return baseUrl + "/" + path;
        }

        return baseUrl + path;
    }

    private void checkConfiguration() {
        if (!properties.isEnabled()) {
            throw new BusinessException(
                    503,
                    "Agent单点登录功能尚未启用"
            );
        }

        if (!StringUtils.hasText(properties.getPmBaseUrl())
                || !StringUtils.hasText(properties.getExchangePath())
                || !StringUtils.hasText(properties.getClientId())
                || !StringUtils.hasText(properties.getClientSecret())) {
            throw new BusinessException(
                    503,
                    "Agent单点登录配置不完整"
            );
        }
    }
}
