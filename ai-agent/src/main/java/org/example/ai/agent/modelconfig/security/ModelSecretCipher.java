package org.example.ai.agent.modelconfig.security;

import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.config.ModelSecurityProperties;
import org.example.ai.agent.common.exception.BusinessException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 模型API Key加解密组件。
 */
@Component
@RequiredArgsConstructor
public class ModelSecretCipher {

    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";
    private static final String CIPHERTEXT_VERSION = "v1";
    private static final int IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder BASE64_ENCODER =
            Base64.getEncoder();
    private static final Base64.Decoder BASE64_DECODER =
            Base64.getDecoder();

    private final ModelSecurityProperties properties;

    /**
     * 加密模型密钥。
     */
    public String encrypt(String plaintext) {
        if (!StringUtils.hasText(plaintext)) {
            throw new BusinessException(400, "模型API Key不能为空");
        }

        try {
            byte[] iv = new byte[IV_LENGTH];
            SECURE_RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    getSecretKey(),
                    new GCMParameterSpec(GCM_TAG_LENGTH, iv)
            );

            byte[] ciphertext = cipher.doFinal(
                    plaintext.getBytes(StandardCharsets.UTF_8)
            );

            return CIPHERTEXT_VERSION
                    + "."
                    + BASE64_ENCODER.encodeToString(iv)
                    + "."
                    + BASE64_ENCODER.encodeToString(ciphertext);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(
                    "模型API Key加密失败",
                    exception
            );
        }
    }

    /**
     * 解密模型密钥。
     */
    public String decrypt(String encryptedValue) {
        if (!StringUtils.hasText(encryptedValue)) {
            throw new BusinessException(503, "模型API Key尚未配置");
        }

        try {
            String[] parts = encryptedValue.split("\\.", 3);
            if (parts.length != 3
                    || !CIPHERTEXT_VERSION.equals(parts[0])) {
                throw new BusinessException(
                        503,
                        "模型API Key密文格式不正确"
                );
            }

            byte[] iv = BASE64_DECODER.decode(parts[1]);
            byte[] ciphertext = BASE64_DECODER.decode(parts[2]);

            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    getSecretKey(),
                    new GCMParameterSpec(GCM_TAG_LENGTH, iv)
            );

            return new String(
                    cipher.doFinal(ciphertext),
                    StandardCharsets.UTF_8
            );
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(
                    503,
                    "模型API Key无法解密，请检查模型加密主密钥"
            );
        }
    }

    /**
     * 解析AES-256主密钥。
     */
    private SecretKeySpec getSecretKey() {
        String configuredKey = properties.getEncryptionKey();
        if (!StringUtils.hasText(configuredKey)) {
            throw new BusinessException(
                    503,
                    "MODEL_CONFIG_ENCRYPTION_KEY尚未配置"
            );
        }

        byte[] keyBytes;
        try {
            keyBytes = BASE64_DECODER.decode(configuredKey);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(
                    503,
                    "模型加密主密钥不是有效的Base64"
            );
        }

        if (keyBytes.length != 32) {
            throw new BusinessException(
                    503,
                    "模型加密主密钥解码后必须是32字节"
            );
        }

        return new SecretKeySpec(keyBytes, KEY_ALGORITHM);
    }
}