package org.example.ai.agent.stability;

import org.example.ai.agent.common.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 外部服务轻量熔断器。
 *
 * 只处理连续网络异常和服务端异常，不把明确的业务4xx响应计为故障。
 */
@Component
public class ExternalServiceCircuitBreaker {

    private final ExternalServiceResilienceProperties properties;
    private final Clock clock;
    private final Map<String, CircuitState> states =
            new ConcurrentHashMap<>();

    public ExternalServiceCircuitBreaker(
            ExternalServiceResilienceProperties properties) {
        this(properties, Clock.systemUTC());
    }

    ExternalServiceCircuitBreaker(
            ExternalServiceResilienceProperties properties,
            Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 外部调用前检查熔断状态。
     */
    public void beforeCall(String serviceName) {
        CircuitState state = states.computeIfAbsent(
                serviceName,
                key -> new CircuitState()
        );
        if (!state.allowRequest(clock.millis())) {
            throw new BusinessException(
                    503,
                    "外部服务暂时不可用，请稍后重试"
            );
        }
    }

    /**
     * 调用成功后关闭熔断并清空连续失败计数。
     */
    public void recordSuccess(String serviceName) {
        states.computeIfAbsent(
                serviceName,
                key -> new CircuitState()
        ).success();
    }

    /**
     * 外部服务返回明确业务响应时，说明网络和服务本身可达。
     */
    public void recordReachable(String serviceName) {
        recordSuccess(serviceName);
    }

    /**
     * 网络或服务端失败后更新熔断状态。
     */
    public void recordFailure(String serviceName) {
        states.computeIfAbsent(
                serviceName,
                key -> new CircuitState()
        ).failure(
                clock.millis(),
                properties.getFailureThreshold(),
                properties.getOpenDurationMs()
        );
    }

    /**
     * 返回当前打开的熔断器数量，供健康检查使用。
     */
    public long openCircuitCount() {
        long now = clock.millis();
        return states.values()
                .stream()
                .filter(state -> state.isOpen(now))
                .count();
    }

    /**
     * 单个外部服务的线程安全熔断状态。
     */
    private static final class CircuitState {

        private int consecutiveFailures;
        private long openUntil;
        private boolean probeInProgress;

        synchronized boolean allowRequest(long now) {
            if (openUntil <= now) {
                if (openUntil > 0) {
                    if (probeInProgress) {
                        return false;
                    }
                    probeInProgress = true;
                }
                return true;
            }
            return false;
        }

        synchronized void success() {
            consecutiveFailures = 0;
            openUntil = 0;
            probeInProgress = false;
        }

        synchronized void failure(
                long now,
                int threshold,
                long openDurationMs) {
            probeInProgress = false;
            consecutiveFailures++;
            if (consecutiveFailures >= threshold) {
                openUntil = now + openDurationMs;
            }
        }

        synchronized boolean isOpen(long now) {
            return openUntil > now;
        }
    }
}
