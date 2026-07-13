package org.example.ai.agent;

import org.example.ai.agent.common.enums.TokenMeasureType;
import org.example.ai.agent.modelusage.model.TokenUsageData;
import org.example.ai.agent.modelusage.support.TokenUsageExtractor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * TokenUsageExtractor 单元测试。
 */
class TokenUsageExtractorTest {

    private final TokenUsageExtractor extractor =
            new TokenUsageExtractor();

    /**
     * 模型响应为空时不能抛异常，
     * 应返回 UNKNOWN 和零 Token。
     */
    @Test
    void shouldReturnUnknownWhenResponseIsNull() {
        TokenUsageData usage = extractor.extract(null);

        assertEquals(0, usage.getPromptTokens());
        assertEquals(0, usage.getCompletionTokens());
        assertEquals(0, usage.getTotalTokens());
        assertEquals(TokenMeasureType.UNKNOWN, usage.getMeasureType());
    }
}