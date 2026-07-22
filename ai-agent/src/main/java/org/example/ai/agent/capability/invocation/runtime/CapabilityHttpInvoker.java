package org.example.ai.agent.capability.invocation.runtime;

import lombok.RequiredArgsConstructor;
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

    public Object invoke(CapabilityHttpRequest request) {
        if (request == null || request.getMethod() == null || request.getUri() == null) {
            throw new IllegalArgumentException( "HTTP请求不能为空" );
        }
        RestClient client = clientFactory.create( request.getTimeoutMs());
        try {
            RestClient.RequestHeadersSpec<?> requestSpec =createRequest(client, request);
            return requestSpec.retrieve().body(Object.class);
        } catch (RestClientResponseException exception) {
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
            throw new CapabilityInvocationException(
                    "BUSINESS_API_TIMEOUT_OR_NETWORK_ERROR",
                    "业务接口超时或网络不可达"
            );

        } catch (RestClientException exception) {
            throw new CapabilityInvocationException(
                    "BUSINESS_API_CALL_FAILED",
                    "业务接口调用失败"
            );
        }
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