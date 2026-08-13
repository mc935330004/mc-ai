package org.example.ai.agent.stability;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;

/**
 * 使用Redis实现多实例一致的固定窗口限流。
 */
@Component
@RequiredArgsConstructor
public class RedisRequestRateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    /**
     * 在同一个Redis命令中完成计数与过期时间设置，避免留下永久限流键。
     */
    private static final DefaultRedisScript<Long> ACQUIRE_SCRIPT =
            new DefaultRedisScript<>(
                    """
                    local current = redis.call('INCR', KEYS[1])
                    if current == 1 then
                        redis.call('PEXPIRE', KEYS[1], ARGV[1])
                    end
                    return current
                    """,
                    Long.class
            );

    private final StringRedisTemplate redisTemplate;
    private final RequestRateLimitProperties properties;

    /**
     * 尝试获取本分钟的一个请求配额。
     */
    public boolean tryAcquire(
            String userId,
            boolean expensive) {
        if (!properties.isEnabled()) {
            return true;
        }
        long window = Instant.now().getEpochSecond()
                / WINDOW.toSeconds();
        String bucket = expensive ? "expensive" : "default";
        String key = properties.getKeyPrefix()
                + bucket
                + ":"
                + userId
                + ":"
                + window;

        Long count = redisTemplate.execute(
                ACQUIRE_SCRIPT,
                Collections.singletonList(key),
                String.valueOf(WINDOW.plusSeconds(5).toMillis())
        );

        int limit = expensive
                ? properties.getExpensiveRequestsPerMinute()
                : properties.getDefaultRequestsPerMinute();
        return count != null && count <= limit;
    }
}
