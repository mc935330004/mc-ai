package org.example.ai.agent.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.capability.entity.CapabilityDefinition;
import org.example.ai.agent.capability.invocation.runtime.CapabilityInvocationException;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.stability.ExternalServiceCircuitBreaker;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.stream.StreamSupport;

/**
 * PM WRITE 能力权限校验器。
 *
 * 直接读取 PM 已有 /user/info 返回的 permissions，
 * 不在 Agent 中复制人员、角色和权限关系。
 */
@Component
@RequiredArgsConstructor
public class PmCapabilityPermissionVerifier {

    private static final String REQUIRED_PERMISSION_FIELD ="x-required-permission";
    private static final String SERVICE_NAME = "pm-write-permission";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final ExternalServiceCircuitBreaker circuitBreaker;

    /**
     * WRITE 能力执行前校验当前 PM 用户权限。
     */
    public void verifyWritePermission(CapabilityDefinition capability,String authorization) {
        if (capability == null || !"WRITE".equalsIgnoreCase( capability.getSideEffect())) {
            return;
        }
        if (!StringUtils.hasText(authorization)) {
            throw invalid(
                    "CAPABILITY_AUTHORIZATION_REQUIRED",
                    "WRITE能力执行缺少业务系统登录凭证"
            );
        }

        String requiredPermission =
                readRequiredPermission(capability);

        JsonNode response;

        checkCircuit();
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
                circuitBreaker.recordReachable(SERVICE_NAME);
                throw invalid(
                        "BUSINESS_PERMISSION_CHECK_FAILED",
                        "业务系统权限接口返回内容为空"
                );
            }
            response = objectMapper.readTree(responseBody);
            circuitBreaker.recordSuccess(SERVICE_NAME);
        } catch (JsonProcessingException exception) {
            circuitBreaker.recordReachable(SERVICE_NAME);
            throw invalid("BUSINESS_PERMISSION_CHECK_FAILED","业务系统权限接口返回格式不正确");
        } catch (RestClientResponseException exception) {
            int status =exception.getStatusCode().value();
            if (status >= 500) {
                circuitBreaker.recordFailure(SERVICE_NAME);
            } else {
                circuitBreaker.recordReachable(SERVICE_NAME);
            }
            if (status == 401 || status == 403) {
                throw invalid("BUSINESS_PERMISSION_TOKEN_INVALID",
                        "业务系统登录状态已失效");
            }
            throw invalid(
                    "BUSINESS_PERMISSION_CHECK_FAILED",
                    "业务系统权限校验失败"
            );

        } catch (RestClientException exception) {
            circuitBreaker.recordFailure(SERVICE_NAME);
            // 权限服务不可用时失败关闭，不能继续调用 WRITE API。
            throw invalid(
                    "BUSINESS_PERMISSION_CHECK_FAILED",
                    "业务系统权限服务暂时不可用"
            );
        }

        JsonNode permissions = response == null
                ? null
                : response.path("data")
                .path("permissions");

        boolean allowed =
                permissions != null
                        && permissions.isArray()
                        && StreamSupport.stream(
                                permissions.spliterator(),
                                false
                        ).map(JsonNode::asText)
                        .anyMatch(
                                requiredPermission::equals
                        );

        if (!allowed) {
            throw invalid(
                    "BUSINESS_PERMISSION_DENIED",
                    "当前用户没有执行该业务操作的权限"
            );
        }
    }

    /**
     * 权限服务熔断时保持能力调用的稳定错误契约。
     */
    private void checkCircuit() {
        try {
            circuitBreaker.beforeCall(SERVICE_NAME);
        } catch (BusinessException exception) {
            throw invalid(
                    "BUSINESS_PERMISSION_CHECK_FAILED",
                    "业务系统权限服务暂时不可用"
            );
        }
    }

    /**
     * 从能力输入 Schema 中读取 PM 权限编码。
     */
    private String readRequiredPermission(
            CapabilityDefinition capability) {

        if (!StringUtils.hasText(
                capability.getInputSchemaJson())) {
            throw invalid(
                    "CAPABILITY_PERMISSION_CONFIG_INVALID",
                    "WRITE能力缺少输入Schema"
            );
        }

        try {
            JsonNode schema = objectMapper.readTree(
                    capability.getInputSchemaJson()
            );

            String permission = schema.path(
                    REQUIRED_PERMISSION_FIELD
            ).asText(null);

            if (!StringUtils.hasText(permission)) {
                throw invalid(
                        "CAPABILITY_PERMISSION_REQUIRED",
                        "WRITE能力未配置业务权限编码"
                );
            }

            return permission.trim();

        } catch (JsonProcessingException exception) {
            throw invalid(
                    "CAPABILITY_PERMISSION_CONFIG_INVALID",
                    "WRITE能力权限配置格式不正确"
            );
        }
    }

    private CapabilityInvocationException invalid(
            String errorCode,
            String errorMessage) {

        return new CapabilityInvocationException(
                errorCode,
                errorMessage
        );
    }
}
