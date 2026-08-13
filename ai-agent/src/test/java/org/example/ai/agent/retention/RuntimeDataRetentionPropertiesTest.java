package org.example.ai.agent.retention;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 运行数据保留配置测试。
 */
class RuntimeDataRetentionPropertiesTest {

    @Test
    void shouldAcceptAuditRetentionLongerThanRuntimeRetention() {
        RuntimeDataRetentionProperties properties =
                new RuntimeDataRetentionProperties();
        properties.setEnabled(true);

        assertDoesNotThrow(properties::validate);
    }

    @Test
    void shouldRejectAuditRetentionShorterThanRuntimeRetention() {
        RuntimeDataRetentionProperties properties =
                new RuntimeDataRetentionProperties();
        properties.setEnabled(true);
        properties.setRuntimeDays(90);
        properties.setAuditDays(30);

        assertThrows(
                IllegalStateException.class,
                properties::validate
        );
    }
}
