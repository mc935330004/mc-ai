package org.example.ai.agent.modelconfig.model;

/**
 * 模型授权、能力过滤和聊天选模使用的轻量信息。
 *
 * 不包含API地址和密钥信息。
 */
public record ModelOption(
        String modelCode,
        String displayName,
        String providerCode,
        boolean defaultModel,
        boolean streamingSupported,
        boolean structuredOutputSupported,
        boolean toolCallingSupported
) {
}