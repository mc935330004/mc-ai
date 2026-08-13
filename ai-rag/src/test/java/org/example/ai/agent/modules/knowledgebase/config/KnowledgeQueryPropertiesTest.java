package org.example.ai.agent.modules.knowledgebase.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 知识库查询线程池配置测试。
 */
class KnowledgeQueryPropertiesTest {

    @Test
    void shouldAcceptBoundedExecutorConfiguration() {
        KnowledgeQueryProperties properties =
                new KnowledgeQueryProperties();

        assertDoesNotThrow(properties::validate);
    }

    @Test
    void shouldRejectMaximumSmallerThanCoreSize() {
        KnowledgeQueryProperties properties =
                new KnowledgeQueryProperties();
        properties.setExecutorCoreSize(8);
        properties.setExecutorMaxSize(4);

        assertThrows(
                IllegalStateException.class,
                properties::validate
        );
    }
}
