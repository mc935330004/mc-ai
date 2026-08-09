package org.example.ai.agent.modelconfig.model;

import java.math.BigDecimal;

/**
 * 服务端运行时模型配置。
 *
 * apiKey只允许存在于服务端内存，
 * 禁止序列化后返回前端或写入日志。
 */
public record ModelRuntimeConfig(
        String modelCode,
        String displayName,
        String providerCode,
        String apiType,
        String baseUrl,
        String apiKey,
        String modelName,
        BigDecimal temperature,
        Integer maxTokens,
        Integer timeoutSeconds,
        boolean streamingSupported,
        boolean structuredOutputSupported,
        boolean toolCallingSupported,
        Integer contextWindow,
        boolean defaultModel,
        boolean enabled
) {
}