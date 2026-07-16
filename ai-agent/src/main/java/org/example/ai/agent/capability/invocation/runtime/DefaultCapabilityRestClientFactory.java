package org.example.ai.agent.capability.invocation.runtime;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 为每次能力调用创建带超时限制的 RestClient。
 *
 * 不自动跟随重定向，避免业务接口把认证信息重定向到其他域名。
 */
@Component
public class DefaultCapabilityRestClientFactory
        implements CapabilityRestClientFactory {

    @Override
    public RestClient create(int timeoutMs) {
        Duration timeout =
                Duration.ofMillis(timeoutMs);

        HttpClient httpClient =
                HttpClient.newBuilder()
                        .connectTimeout(timeout)
                        .followRedirects(
                                HttpClient.Redirect.NEVER
                        )
                        .build();

        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(httpClient);

        requestFactory.setReadTimeout(timeout);

        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }
}