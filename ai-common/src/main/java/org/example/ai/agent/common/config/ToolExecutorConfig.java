package org.example.ai.agent.common.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * ToolExecutor 相关配置。
 */
@Configuration
@EnableConfigurationProperties(BusinessApiProperties.class)
public class ToolExecutorConfig {

    /**
     * Spring RestClient。
     *
     * 用于调用真实业务系统接口。
     * 后续如果业务接口需要统一鉴权，可以在这里加默认 Header。
     */
    @Bean
    public RestClient restClient(BusinessApiProperties businessApiProperties) {
        return RestClient.builder()
                .baseUrl(businessApiProperties.getBaseUrl())
                .build();
    }
}