package org.example.ai.agent.modelusage.model;

import lombok.Builder;
import lombok.Data;
import org.example.ai.agent.common.enums.TokenMeasureType;

/**
 * 单次模型调用的 Token 使用数据。
 */
@Data
@Builder
public class TokenUsageData {

    /**
     * 输入提示词消耗的 Token。
     */
    private int promptTokens;

    /**
     * 模型生成内容消耗的 Token。
     */
    private int completionTokens;

    /**
     * 输入和输出总 Token。
     */
    private int totalTokens;

    /**
     * 从供应商提示词缓存读取的 Token。
     */
    private Long cacheReadTokens;

    /**
     * 写入供应商提示词缓存的 Token。
     */
    private Long cacheWriteTokens;

    /**
     * Token 计量方式。
     */
    private TokenMeasureType measureType;

    /**
     * 创建一个无法获取 Token 时的默认值。
     */
    public static TokenUsageData unknown() {
        return TokenUsageData.builder()
                .promptTokens(0)
                .completionTokens(0)
                .totalTokens(0)
                .measureType(TokenMeasureType.UNKNOWN)
                .build();
    }
}