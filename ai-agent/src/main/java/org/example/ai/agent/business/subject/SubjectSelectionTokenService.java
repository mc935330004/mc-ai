package org.example.ai.agent.business.subject;

import org.example.ai.agent.business.model.BusinessSubjectType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

/**
 * 为主体候选签发短期不透明选择令牌。
 *
 * 令牌使用进程随机 AES-GCM 密钥，并绑定当前用户、会话和候选类型；进程重启后旧令牌自然失效，用户重新搜索即可。
 */
@Component
public final class SubjectSelectionTokenService {

    private static final byte VERSION = 1;
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int MAX_TOKEN_LENGTH = 4096;
    private static final byte[] AAD =
            "mc-ai-subject-selection-v1".getBytes(StandardCharsets.UTF_8);

    private final byte[] key;
    private final Clock clock;
    private final Duration ttl;
    private final SecureRandom secureRandom;

    @Autowired
    public SubjectSelectionTokenService(
            @Value("${ai.business.subject.selection-token-ttl-minutes:10}")
            long ttlMinutes) {
        this(randomKey(), Clock.systemUTC(), Duration.ofMinutes(ttlMinutes));
    }

    SubjectSelectionTokenService(
            byte[] key,
            Clock clock,
            Duration ttl) {
        if (key == null || key.length != KEY_BYTES) {
            throw new IllegalArgumentException("主体选择令牌密钥必须是32字节");
        }
        if (ttl == null || ttl.isNegative() || ttl.isZero()
                || ttl.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("主体选择令牌TTL必须在1秒到1小时之间");
        }
        this.key = key.clone();
        this.clock = Objects.requireNonNull(clock, "clock不能为空");
        this.ttl = ttl;
        this.secureRandom = new SecureRandom();
    }

    /**
     * 原始主体标识只进入加密载荷，外部候选只持有返回的不透明字符串。
     */
    public String issue(
            String rawSubjectId,
            String userId,
            String sessionId,
            BusinessSubjectType type) {
        requireBoundText(rawSubjectId, "rawSubjectId", 256);
        requireBoundText(userId, "userId", 256);
        requireBoundText(sessionId, "sessionId", 256);
        Objects.requireNonNull(type, "type不能为空");
        try {
            byte[] iv = new byte[IV_BYTES];
            secureRandom.nextBytes(iv);
            byte[] plain = payload(
                    rawSubjectId,
                    userId,
                    sessionId,
                    type,
                    clock.instant().plus(ttl).toEpochMilli()
            );
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv)
            );
            cipher.updateAAD(AAD);
            byte[] encrypted = cipher.doFinal(plain);
            byte[] token = new byte[1 + iv.length + encrypted.length];
            token[0] = VERSION;
            System.arraycopy(iv, 0, token, 1, iv.length);
            System.arraycopy(encrypted, 0, token, 1 + iv.length, encrypted.length);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
        } catch (Exception exception) {
            throw new IllegalStateException("主体选择令牌签发失败", exception);
        }
    }

    /**
     * 只有上下文完全一致且令牌未过期时返回内部主体标识；调用方仍须重新查询来源授权目录。
     */
    public Optional<String> resolve(
            String token,
            String userId,
            String sessionId,
            BusinessSubjectType type) {
        if (!StringUtils.hasText(token)
                || token.length() > MAX_TOKEN_LENGTH
                || !StringUtils.hasText(userId)
                || !StringUtils.hasText(sessionId)
                || type == null) {
            return Optional.empty();
        }
        try {
            byte[] encoded = Base64.getUrlDecoder().decode(token);
            if (encoded.length <= 1 + IV_BYTES + 16 || encoded[0] != VERSION) {
                return Optional.empty();
            }
            byte[] iv = Arrays.copyOfRange(encoded, 1, 1 + IV_BYTES);
            byte[] encrypted = Arrays.copyOfRange(encoded, 1 + IV_BYTES, encoded.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv)
            );
            cipher.updateAAD(AAD);
            return readPayload(cipher.doFinal(encrypted), userId, sessionId, type);
        } catch (Exception ignored) {
            // 无效、篡改或由旧进程签发的令牌统一视为不可选择，不向外暴露失败细节。
            return Optional.empty();
        }
    }

    private byte[] payload(
            String rawSubjectId,
            String userId,
            String sessionId,
            BusinessSubjectType type,
            long expiresAt) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeByte(VERSION);
            output.writeLong(expiresAt);
            output.writeUTF(userId);
            output.writeUTF(sessionId);
            output.writeUTF(type.name());
            output.writeUTF(rawSubjectId);
        }
        return bytes.toByteArray();
    }

    private Optional<String> readPayload(
            byte[] plain,
            String expectedUserId,
            String expectedSessionId,
            BusinessSubjectType expectedType) throws Exception {
        try (ByteArrayInputStream bytes = new ByteArrayInputStream(plain);
             DataInputStream input = new DataInputStream(bytes)) {
            byte version = input.readByte();
            long expiresAt = input.readLong();
            String userId = input.readUTF();
            String sessionId = input.readUTF();
            String type = input.readUTF();
            String rawSubjectId = input.readUTF();
            if (bytes.available() != 0
                    || version != VERSION
                    || expiresAt <= clock.instant().toEpochMilli()
                    || !expectedUserId.equals(userId)
                    || !expectedSessionId.equals(sessionId)
                    || !expectedType.name().equals(type)
                    || !StringUtils.hasText(rawSubjectId)) {
                return Optional.empty();
            }
            return Optional.of(rawSubjectId);
        }
    }

    private static byte[] randomKey() {
        byte[] key = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(key);
        return key;
    }

    private void requireBoundText(
            String value,
            String field,
            int maxBytes) {
        if (!StringUtils.hasText(value)
                || value.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new IllegalArgumentException(field + "不合法");
        }
    }
}
