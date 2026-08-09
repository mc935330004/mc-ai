package org.example.ai.agent.common.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * ToolExecutor 相关配置。
 */
@Configuration
@EnableConfigurationProperties(BusinessApiProperties.class)
public class ToolExecutorConfig {

    /**
     * 创建调用 PM 业务系统的公共客户端。
     *
     * 统一应用连接超时和读取超时，
     * 避免页面空闲后请求长时间挂起。
     */
    @Bean
    public RestClient restClient(BusinessApiProperties properties) {

        Duration connectTimeout = Duration.ofMillis(
                properties.getConnectTimeoutMs()
        );

        Duration readTimeout = Duration.ofMillis(
                properties.getReadTimeoutMs()
        );

        /*
         * 不自动跟随重定向，
         * 防止 Authorization 被转发到其他地址。
         */
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(httpClient);

        requestFactory.setReadTimeout(readTimeout);

        return RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}