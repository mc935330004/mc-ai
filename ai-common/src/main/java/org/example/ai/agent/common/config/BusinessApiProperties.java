package org.example.ai.agent.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 真实业务接口配置。
 *
 * 不保存 Token、密码或其他敏感凭证，
 * 只负责绑定业务系统地址和超时时间。
 */
@Data
@ConfigurationProperties(prefix = "agent.business-api")
public class BusinessApiProperties {

    /**
     * 业务系统基础地址。
     */
    private String baseUrl;

    /**
     * 连接超时时间，单位毫秒。
     */
    private int connectTimeoutMs = 86400;

    /**
     * 读取超时时间，单位毫秒。
     */
    private int readTimeoutMs = 86400;
}