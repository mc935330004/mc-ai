package org.example.ai.agent.stability;

import org.example.ai.agent.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 外部服务熔断行为测试。
 */
class ExternalServiceCircuitBreakerTest {

    @Test
    void shouldOpenAfterConfiguredConsecutiveFailures() {
        ExternalServiceResilienceProperties properties =
                new ExternalServiceResilienceProperties();
        properties.setFailureThreshold(2);
        properties.setOpenDurationMs(30_000L);

        ExternalServiceCircuitBreaker circuitBreaker =
                new ExternalServiceCircuitBreaker(
                        properties,
                        Clock.fixed(
                                Instant.parse("2026-08-13T00:00:00Z"),
                                ZoneOffset.UTC
                        )
                );

        circuitBreaker.recordFailure("pm-user-info");
        assertDoesNotThrow(
                () -> circuitBreaker.beforeCall("pm-user-info")
        );

        circuitBreaker.recordFailure("pm-user-info");
        assertEquals(1L, circuitBreaker.openCircuitCount());
        assertThrows(
                BusinessException.class,
                () -> circuitBreaker.beforeCall("pm-user-info")
        );
    }

    @Test
    void shouldResetFailuresAfterSuccessfulCall() {
        ExternalServiceResilienceProperties properties =
                new ExternalServiceResilienceProperties();
        properties.setFailureThreshold(2);

        ExternalServiceCircuitBreaker circuitBreaker =
                new ExternalServiceCircuitBreaker(properties);

        circuitBreaker.recordFailure("business-api");
        circuitBreaker.recordSuccess("business-api");
        circuitBreaker.recordFailure("business-api");

        assertDoesNotThrow(
                () -> circuitBreaker.beforeCall("business-api")
        );
    }
}
