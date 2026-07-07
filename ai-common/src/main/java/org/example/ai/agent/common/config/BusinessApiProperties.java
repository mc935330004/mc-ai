package org.example.ai.agent.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 真实业务接口配置。
 *
 * 注意：
 * 不要把 Token、密码、API Key 写死在代码里。
 * 如果后续业务接口需要鉴权，优先从环境变量或配置中心读取。
 */
@Data
@Configuration(proxyBeanMethods = false)
@ConfigurationProperties(prefix = "agent.business-api")
public class BusinessApiProperties {

    /**
     * 业务系统基础地址。
     *
     */
    private String baseUrl;

    /**
     * 连接超时时间，单位毫秒。
     */
    private int connectTimeoutMs = 5000;

    /**
     * 读取超时时间，单位毫秒。
     */
    private int readTimeoutMs = 10000;
}