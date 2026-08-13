package org.example.ai.agent.capability.invocation.runtime;

import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.stability.ExternalServiceCircuitBreaker;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * HTTP 传输适配器。
 *
 * 不负责变量解析和 URL 拼接，
 * 只执行已经编译完成的 CapabilityHttpRequest。
 */
@Component
@RequiredArgsConstructor
public class CapabilityHttpInvoker {

    private final CapabilityRestClientFactory clientFactory;
    private final ExternalServiceCircuitBreaker circuitBreaker;

    public Object invoke(CapabilityHttpRequest request) {
        if (request == null || request.getMethod() == null || request.getUri() == null) {
            throw new IllegalArgumentException( "HTTP请求不能为空" );
        }
        String serviceName = resolveServiceName(request);
        checkCircuit(serviceName);
        RestClient client = clientFactory.create( request.getTimeoutMs());
        try {
            RestClient.RequestHeadersSpec<?> requestSpec =createRequest(client, request);
            Object result = requestSpec.retrieve().body(Object.class);
            circuitBreaker.recordSuccess(serviceName);
            return result;
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().is5xxServerError()) {
                circuitBreaker.recordFailure(serviceName);
            } else {
                circuitBreaker.recordReachable(serviceName);
            }
            /*
             * 不把业务系统响应正文放进错误消息，
             * 避免敏感信息进入运行轨迹。
             */
            throw new CapabilityInvocationException(
                    "BUSINESS_API_HTTP_ERROR",
                    "业务接口返回HTTP状态：" +
                            exception.getStatusCode().value()
            );

        } catch (ResourceAccessException exception) {
            circuitBreaker.recordFailure(serviceName);
            throw new CapabilityInvocationException(
                    "BUSINESS_API_TIMEOUT_OR_NETWORK_ERROR",
                    "业务接口超时或网络不可达"
            );

        } catch (RestClientException exception) {
            circuitBreaker.recordFailure(serviceName);
            throw new CapabilityInvocationException(
                    "BUSINESS_API_CALL_FAILED",
                    "业务接口调用失败"
            );
        }
    }

    /**
     * 将通用熔断异常转换为能力执行域内的稳定错误码。
     */
    private void checkCircuit(String serviceName) {
        try {
            circuitBreaker.beforeCall(serviceName);
        } catch (BusinessException exception) {
            throw new CapabilityInvocationException(
                    "BUSINESS_API_CIRCUIT_OPEN",
                    "业务接口暂时不可用，请稍后重试"
            );
        }
    }

    /**
     * 按目标主机隔离熔断状态，避免一个业务系统故障阻断其他系统。
     */
    private String resolveServiceName(CapabilityHttpRequest request) {
        String authority = request.getUri().getAuthority();
        if (authority == null || authority.isBlank()) {
            return "business-api:relative";
        }
        return "business-api:" + authority.toLowerCase();
    }

    private RestClient.RequestHeadersSpec<?> createRequest(RestClient client, CapabilityHttpRequest request) {
        HttpMethod method = request.getMethod();
        /*
         * HttpMethod 在 Spring 7 中不是 enum，
         * 因此使用 equals 判断，不能使用 switch case。
         */
        if (HttpMethod.GET.equals(method)) {
            return client.get()
                    .uri(request.getUri())
                    .headers(headers ->headers.addAll(request.getHeaders()));
        }

        if (HttpMethod.DELETE.equals(method)) {
            return client.delete()
                    .uri(request.getUri())
                    .headers(headers ->
                            headers.addAll(
                                    request.getHeaders()
                            )
                    );
        }

        /*
         * 只有 POST、PUT、PATCH 允许进入 RequestBody 请求分支。
         */
        boolean bodyMethod = HttpMethod.POST.equals(method)|| HttpMethod.PUT.equals(method) || HttpMethod.PATCH.equals(method);
        if (!bodyMethod) {
            throw new CapabilityInvocationException(
                    "CAPABILITY_HTTP_METHOD_INVALID",
                    "不支持的HTTP方法：" + method
            );
        }

        /*
         * Spring 7 的 RestClient 提供 method(HttpMethod)，
         * 可以直接根据运行时方法创建 RequestBodyUriSpec。
         */
        RestClient.RequestBodyUriSpec bodyRequest =
                client.method(method);

        RestClient.RequestBodySpec prepared =
                bodyRequest
                        .uri(request.getUri())
                        .headers(headers ->
                                headers.addAll(
                                        request.getHeaders()
                                )
                        );

        return request.getBody() == null ? prepared : prepared.body(request.getBody());
    }
}
