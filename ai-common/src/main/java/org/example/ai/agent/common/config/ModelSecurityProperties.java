package org.example.ai.agent.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 模型密钥加密配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.agent.model-security")
public class ModelSecurityProperties {

    /**
     * Base64编码的32字节AES主密钥。
     */
    private String encryptionKey;
}