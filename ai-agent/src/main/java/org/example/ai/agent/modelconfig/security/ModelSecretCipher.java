package org.example.ai.agent.modelconfig.security;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.config.ModelSecurityProperties;
import org.example.ai.agent.common.exception.BusinessException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 模型API Key加解密组件。
 *
 * 使用AES-256-GCM保证密文机密性和完整性。
 * 支持当前密钥和上一密钥平滑轮换。
 */
@Component
@RequiredArgsConstructor
public class ModelSecretCipher {

    private static final String CIPHER_ALGORITHM ="AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";
    private static final String CIPHERTEXT_VERSION = "v1";

    private static final int IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final int AES_256_KEY_LENGTH = 32;

    private static final SecureRandom SECURE_RANDOM =  new SecureRandom();
    private static final Base64.Encoder BASE64_ENCODER =Base64.getEncoder();
    private static final Base64.Decoder BASE64_DECODER =Base64.getDecoder();
    private final ModelSecurityProperties properties;

    /**
     * 应用启动时立即校验主密钥。
     *
     * 禁止等到第一次模型调用时才发现密钥配置错误。
     */
    @PostConstruct
    public void validateConfiguration() {
        resolveSecretKey(properties.getEncryptionKey(),"MODEL_CONFIG_ENCRYPTION_KEY");
        if (StringUtils.hasText(properties.getPreviousEncryptionKey())) {
            resolveSecretKey(properties.getPreviousEncryptionKey(),"MODEL_CONFIG_PREVIOUS_ENCRYPTION_KEY");
        }
    }

    /**
     * 使用当前主密钥加密模型API Key。
     */
    public String encrypt(String plaintext) {
        if (!StringUtils.hasText(plaintext)) {
            throw new BusinessException(
                    400,
                    "模型API Key不能为空"
            );
        }

        try {
            byte[] iv = new byte[IV_LENGTH];
            SECURE_RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    resolveSecretKey(
                            properties.getEncryptionKey(),
                            "MODEL_CONFIG_ENCRYPTION_KEY"
                    ),
                    new GCMParameterSpec(
                            GCM_TAG_LENGTH,
                            iv
                    )
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
        } catch (GeneralSecurityException exception) {
            throw new BusinessException(
                    "模型API Key加密失败",
                    exception
            );
        }
    }

    /**
     * 解密模型API Key。
     *
     * 优先使用当前密钥；
     * 当前密钥无法解密时，才尝试上一密钥。
     */
    public String decrypt(String encryptedValue) {
        CipherPayload payload =
                parseCiphertext(encryptedValue);

        try {
            return decryptWithKey(
                    payload,
                    properties.getEncryptionKey(),
                    "MODEL_CONFIG_ENCRYPTION_KEY"
            );
        } catch (GeneralSecurityException currentException) {
            /*
             * 当前密钥解密失败时，
             * 只有明确配置了上一密钥才允许继续尝试。
             */
            if (!StringUtils.hasText(
                    properties.getPreviousEncryptionKey())) {

                throw unableToDecrypt();
            }
        }

        try {
            return decryptWithKey(
                    payload,
                    properties.getPreviousEncryptionKey(),
                    "MODEL_CONFIG_PREVIOUS_ENCRYPTION_KEY"
            );
        } catch (GeneralSecurityException previousException) {
            throw unableToDecrypt();
        }
    }

    /**
     * 使用指定主密钥解密密文。
     */
    private String decryptWithKey(
            CipherPayload payload,
            String configuredKey,
            String propertyName)
            throws GeneralSecurityException {

        Cipher cipher =
                Cipher.getInstance(CIPHER_ALGORITHM);

        cipher.init(
                Cipher.DECRYPT_MODE,
                resolveSecretKey(
                        configuredKey,
                        propertyName
                ),
                new GCMParameterSpec(
                        GCM_TAG_LENGTH,
                        payload.iv()
                )
        );

        return new String(
                cipher.doFinal(payload.ciphertext()),
                StandardCharsets.UTF_8
        );
    }

    /**
     * 解析现有v1密文。
     */
    private CipherPayload parseCiphertext(
            String encryptedValue) {

        if (!StringUtils.hasText(encryptedValue)) {
            throw new BusinessException(
                    503,
                    "模型API Key尚未配置"
            );
        }

        try {
            String[] parts =
                    encryptedValue.split("\\.", 3);

            if (parts.length != 3
                    || !CIPHERTEXT_VERSION.equals(
                    parts[0])) {

                throw invalidCiphertext();
            }

            byte[] iv =
                    BASE64_DECODER.decode(parts[1]);

            byte[] ciphertext =
                    BASE64_DECODER.decode(parts[2]);

            /*
             * AES-GCM的IV固定为12字节；
             * 密文至少需要包含16字节认证标签。
             */
            if (iv.length != IV_LENGTH
                    || ciphertext.length < 16) {

                throw invalidCiphertext();
            }

            return new CipherPayload(
                    iv,
                    ciphertext
            );
        } catch (BusinessException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw invalidCiphertext();
        }
    }

    /**
     * 解析并校验Base64格式的AES-256主密钥。
     */
    private SecretKeySpec resolveSecretKey(
            String configuredKey,
            String propertyName) {

        if (!StringUtils.hasText(configuredKey)) {
            throw new BusinessException(
                    503,
                    propertyName + "尚未配置"
            );
        }

        byte[] keyBytes;

        try {
            keyBytes =
                    BASE64_DECODER.decode(configuredKey);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(
                    503,
                    propertyName
                            + "不是有效的Base64"
            );
        }

        if (keyBytes.length != AES_256_KEY_LENGTH) {
            throw new BusinessException(
                    503,
                    propertyName
                            + "解码后必须是32字节"
            );
        }

        return new SecretKeySpec(
                keyBytes,
                KEY_ALGORITHM
        );
    }

    /**
     * 构建统一的密文格式异常。
     */
    private BusinessException invalidCiphertext() {
        return new BusinessException(
                503,
                "模型API Key密文格式不正确"
        );
    }

    /**
     * 构建不暴露密钥细节的解密异常。
     */
    private BusinessException unableToDecrypt() {
        return new BusinessException(
                503,
                "模型API Key无法解密，"
                        + "请检查当前主密钥和上一主密钥"
        );
    }

    /**
     * 已解析的密文载荷。
     */
    private record CipherPayload(
            byte[] iv,
            byte[] ciphertext) {
    }
}