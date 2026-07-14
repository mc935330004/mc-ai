package org.example.ai.agent.chat.support;

import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.config.AgentStreamProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Set;

/**
 * Agent SSE 协议版本决策器。
 *
 * 决策优先级：
 * 1. 明确请求 v1，直接使用 v1。
 * 2. 服务端关闭 v2，回退 v1。
 * 3. denyUsers 强制使用 v1。
 * 4. allowUsers 强制使用 v2。
 * 5. 其余用户按固定哈希百分比灰度。
 */
@Component
@RequiredArgsConstructor
public class AgentStreamVersionResolver {

    private final AgentStreamProperties properties;

    /**
     * 解析最终生效的协议版本。
     */
    public int resolve(Integer requestedVersion, String userId) {
        if (Integer.valueOf(1).equals(requestedVersion)) {
            return 1;
        }

        boolean wantsV2 = Integer.valueOf(2).equals(requestedVersion)
                || (requestedVersion == null && properties.getDefaultVersion() == 2);

        if (!wantsV2 || !properties.isV2Enabled()) {
            return 1;
        }
        String normalizedUserId = normalizeUserId(userId);
        if (contains(properties.getV2DenyUsers(), normalizedUserId)) {
            return 1;
        }
        if (contains(properties.getV2AllowUsers(), normalizedUserId)) {
            return 2;
        }

        int percentage = Math.max( 0, Math.min(properties.getV2RolloutPercentage(), 100));

        if (percentage <= 0) {
            return 1;
        }

        if (percentage >= 100) {
            return 2;
        }

        /*
         * 使用用户ID做固定哈希。
         *
         * 同一个用户会稳定落在同一个协议版本，
         * 不会在多次请求间随机切换。
         */
        int bucket = Math.floorMod(normalizedUserId.hashCode(), 100 );

        return bucket < percentage ? 2 : 1;
    }

    private boolean contains(Set<String> values, String userId) {
        if (values == null || values.isEmpty()) {
            return false;
        }

        return values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .anyMatch(userId::equals);
    }

    private String normalizeUserId(String userId) {
        return StringUtils.hasText(userId) ? userId.trim() : "anonymous";
    }
}