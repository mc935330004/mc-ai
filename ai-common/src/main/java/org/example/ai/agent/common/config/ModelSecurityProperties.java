package org.example.ai.agent.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 模型API Key加密主密钥配置。
 *
 * 当前密钥用于加密新数据；
 * 上一密钥只用于密钥轮换期间解密旧数据。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.agent.model-security")
public class ModelSecurityProperties {

    /**
     * 当前使用的AES-256主密钥。
     *
     * 必须是Base64编码后的32字节随机密钥。
     */
    private String encryptionKey;

    /**
     * 上一次使用的AES-256主密钥。
     *
     * 仅在主密钥轮换期间配置；
     * 旧密文完成重新保存后必须删除该配置。
     */
    private String previousEncryptionKey;
}