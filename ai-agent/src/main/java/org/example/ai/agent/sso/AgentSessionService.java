package org.example.ai.agent.sso;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.sso.dto.PmSsoIdentity;
import org.example.ai.agent.sso.model.AgentSession;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.util.WebUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * Agent服务端安全会话服务。
 *
 * 浏览器只保存随机会话ID。
 * PM Token使用AES-GCM加密后保存到Redis。
 */
@Service
@RequiredArgsConstructor
public class AgentSessionService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Encoder BASE64_ENCODER = Base64.getEncoder();
    private static final Base64.Decoder BASE64_DECODER = Base64.getDecoder();

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AgentSsoProperties properties;
    private final HttpServletRequest request;

    /**
     * 创建Agent会话，返回随机会话ID。
     */
    public String createSession(PmSsoIdentity identity) {
        /*
         *  
         * 同一浏览器重新进行SSO登录时先销毁旧会话，
         * 避免旧会话长期并存。
         *
         * Ticket已经在PM完成验证，因此此处删除旧会话是安全的。
         */
        deleteCurrentSession();
        long ttlSeconds = resolveTtlSeconds(identity);

        AgentSession session = new AgentSession();
        session.setPmUserId(identity.getUserId());
        session.setUsername(identity.getUsername().trim());
        session.setTenantId(identity.getTenantId());
        session.setDeptId(identity.getDeptId());
        session.setPermissions(identity.getPermissions());
        session.setTarget(identity.getTarget());
        session.setEncryptedPmAccessToken(
                encrypt(identity.getPmAccessToken())
        );
        session.setExpiresAt( System.currentTimeMillis()+ Duration.ofSeconds(ttlSeconds).toMillis());
        String sessionId = generateSessionId();
        try {
            redisTemplate.opsForValue().set(
                    buildSessionKey(sessionId),
                    objectMapper.writeValueAsString(session),
                    ttlSeconds,
                    TimeUnit.SECONDS );
        } catch (JsonProcessingException exception) {
            throw new BusinessException(
                    500,
                    "Agent会话创建失败"
            );
        }

        return sessionId;
    }

    /**
     * 获取当前请求的有效会话。
     */
    public AgentSession getRequiredSession() {
        AgentSession session = getCurrentSession();

        if (session == null) {
            throw new BusinessException(401, "Agent登录状态不存在或已过期");
        }

        return session;
    }

    /**
     * 当前请求可能没有会话，因此允许返回null。
     */
    public AgentSession getCurrentSession() {
        String sessionId = getCurrentSessionId();
        if (!StringUtils.hasText(sessionId)) {
            return null;
        }
        String key = buildSessionKey(sessionId);
        String json = redisTemplate.opsForValue().get(key);
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            AgentSession session = objectMapper.readValue(json, AgentSession.class);
            if (session.getExpiresAt() == null || session.getExpiresAt() <= System.currentTimeMillis()) {
                redisTemplate.delete(key);
                return null;
            }

            return session;
        } catch (JsonProcessingException exception) {
            /*
             * Redis中的异常会话直接删除，不能继续使用。
             */
            redisTemplate.delete(key);
            return null;
        }
    }

    /**
     * 从当前会话恢复PM Authorization。
     */
    public String getRequiredAuthorization() {
        AgentSession session = getRequiredSession();
        String pmAccessToken = decrypt(session.getEncryptedPmAccessToken());

        if (!StringUtils.hasText(pmAccessToken)) {
            throw new BusinessException(401,"Agent会话中的PM登录凭证无效");
        }
        return "Bearer " + pmAccessToken;
    }

    /**
     * 删除当前会话。
     */
    public void deleteCurrentSession() {
        String sessionId = getCurrentSessionId();
        if (StringUtils.hasText(sessionId)) {
            redisTemplate.delete(buildSessionKey(sessionId));
        }
    }

    public long getSessionTtlSeconds() {
        return properties.getSessionTtlSeconds() > 0
                ? properties.getSessionTtlSeconds()
                : 1800L;
    }

    private long resolveTtlSeconds(PmSsoIdentity identity) {
        long configuredTtl = getSessionTtlSeconds();

        if (identity.getPmTokenExpiresAt() == null) {
            return configuredTtl;
        }

        long pmRemainingSeconds =identity.getPmTokenExpiresAt()- System.currentTimeMillis() / 1000L;

        if (pmRemainingSeconds <= 0) {
            throw new BusinessException( 401, "PM登录状态已经过期");
        }

        return Math.min(configuredTtl, pmRemainingSeconds);
    }

    private String getCurrentSessionId() {
        Cookie cookie = WebUtils.getCookie(
                request,
                properties.getSessionCookieName()
        );
        return cookie == null ? null : cookie.getValue();
    }

    private String generateSessionId() {
        byte[] randomBytes = new byte[32];
        SECURE_RANDOM.nextBytes(randomBytes);
        return URL_ENCODER.encodeToString(randomBytes);
    }

    private String buildSessionKey(String sessionId) {
        return AgentSsoConstants.SESSION_CACHE_PREFIX + sessionId;
    }

    /**
     * AES-256-GCM加密PM Token。
     */
    private String encrypt(String value) {
        try {
            byte[] iv = new byte[12];
            SECURE_RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    getEncryptionKey(),
                    new GCMParameterSpec(128, iv)
            );

            byte[] encrypted = cipher.doFinal(
                    value.getBytes(StandardCharsets.UTF_8)
            );

            return BASE64_ENCODER.encodeToString(iv)
                    + "."
                    + BASE64_ENCODER.encodeToString(encrypted);

        } catch (Exception exception) {
            throw new BusinessException(
                    500,
                    "Agent会话凭证加密失败"
            );
        }
    }

    /**
     * AES-256-GCM解密PM Token。
     */
    private String decrypt(String value) {
        if (!StringUtils.hasText(value) || !value.contains(".")) {
            throw new BusinessException( 401,"Agent会话凭证格式不正确" );
        }
        try {
            String[] parts = value.split("\\.", 2);
            byte[] iv = BASE64_DECODER.decode(parts[0]);
            byte[] encrypted = BASE64_DECODER.decode(parts[1]);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    getEncryptionKey(),
                    new GCMParameterSpec(128, iv)
            );

            return new String(
                    cipher.doFinal(encrypted),
                    StandardCharsets.UTF_8
            );

        } catch (Exception exception) {
            throw new BusinessException(
                    401,
                    "Agent会话凭证已失效"
            );
        }
    }

    private SecretKeySpec getEncryptionKey() {
        if (!StringUtils.hasText(
                properties.getTokenEncryptionKey())) {
            throw new BusinessException(
                    503,
                    "Agent会话加密密钥未配置"
            );
        }

        byte[] key;
        try {
            key = BASE64_DECODER.decode(properties.getTokenEncryptionKey());
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(503,"Agent会话加密密钥不是有效的Base64");
        }

        if (key.length != 32) {
            throw new BusinessException(
                    503,
                    "Agent会话加密密钥必须是32字节"
            );
        }

        return new SecretKeySpec(key, "AES");
    }
}