package org.example.ai.agent.stability;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 暴露外部服务熔断状态，不主动发起额外探测请求。
 */
@Component("externalServices")
@RequiredArgsConstructor
public class ExternalServiceHealthIndicator
        implements HealthIndicator {

    private final ExternalServiceCircuitBreaker circuitBreaker;

    @Override
    public Health health() {
        long openCount = circuitBreaker.openCircuitCount();
        if (openCount == 0) {
            return Health.up()
                    .withDetail("openCircuits", 0)
                    .build();
        }
        return Health.down()
                .withDetail("openCircuits", openCount)
                .build();
    }
}
