package org.example.ai.agent.modelconfig.failover;

import org.example.ai.agent.common.enums.ModelFailureCategory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 当前应用实例的模型短时失败状态。
 *
 * 第一版只做本机内存抑制，
 * 不引入Redis分布式熔断。
 */
@Component
public class ModelFailureTracker {

    private final Map<String, Long> blockedUntil =
            new ConcurrentHashMap<>();

    public boolean isBlocked(String modelCode) {
        if (!StringUtils.hasText(modelCode)) {
            return false;
        }

        Long deadline = blockedUntil.get(modelCode);
        if (deadline == null) {
            return false;
        }

        if (deadline <= System.currentTimeMillis()) {
            blockedUntil.remove(modelCode, deadline);
            return false;
        }

        return true;
    }

    public void recordFailure(
            String modelCode,
            ModelFailureCategory category) {

        if (!StringUtils.hasText(modelCode)
                || category == null
                || !category.failoverAllowed()
                || category.suppressMillis() <= 0) {
            return;
        }

        blockedUntil.put(
                modelCode,
                System.currentTimeMillis()
                        + category.suppressMillis()
        );
    }

    public void recordSuccess(String modelCode) {
        if (StringUtils.hasText(modelCode)) {
            blockedUntil.remove(modelCode);
        }
    }
}