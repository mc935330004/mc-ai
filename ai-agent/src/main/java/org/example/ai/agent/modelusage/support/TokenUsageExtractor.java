package org.example.ai.agent.modelusage.support;

import org.example.ai.agent.common.enums.TokenMeasureType;
import org.example.ai.agent.modelusage.model.TokenUsageData;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;

/**
 * 从 Spring AI ChatResponse 中提取 Token 使用数据。
 */
@Component
public class TokenUsageExtractor {

    /**
     * 从模型响应元数据中提取 Token。
     *
     * 注意：
     * 不同 OpenAI 兼容供应商不一定完整返回 Usage，
     * 因此这里必须做好空值兼容。
     *
     * @param response Spring AI 模型响应
     * @return Token 使用数据
     */
    public TokenUsageData extract(ChatResponse response) {
        if (response == null || response.getMetadata() == null  || response.getMetadata().getUsage() == null) {
            return TokenUsageData.unknown();
        }

        Usage usage = response.getMetadata().getUsage();

        int promptTokens = safeInteger(usage.getPromptTokens());
        int completionTokens = safeInteger(usage.getCompletionTokens());

        Integer providerTotalTokens = usage.getTotalTokens();
        int totalTokens = providerTotalTokens == null ? promptTokens + completionTokens
                : Math.max(providerTotalTokens, 0);

        /*
         * 如果供应商返回的三个 Token 值全部为 0，
         * 无法判断供应商是否真正提供了 Usage，因此标记为 UNKNOWN。
         */
        TokenMeasureType measureType = promptTokens > 0 || completionTokens > 0 || totalTokens > 0 ? TokenMeasureType.PROVIDER
                        : TokenMeasureType.UNKNOWN;

        return TokenUsageData.builder()
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .totalTokens(totalTokens)
                .cacheReadTokens(usage.getCacheReadInputTokens())
                .cacheWriteTokens(usage.getCacheWriteInputTokens())
                .measureType(measureType)
                .build();
    }

    /**
     * 将可能为空或小于零的 Integer 转换为安全值。
     */
    private int safeInteger(Integer value) {
        return value == null ? 0 : Math.max(value, 0);
    }
}