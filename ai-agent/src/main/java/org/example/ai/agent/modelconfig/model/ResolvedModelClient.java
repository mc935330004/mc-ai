package org.example.ai.agent.modelconfig.model;

import org.springframework.ai.chat.client.ChatClient;

/**
 * 本次模型调用实际使用的客户端和模型信息。
 *
 * 不保存API Key，避免密钥被其他业务代码持有。
 */
public record ResolvedModelClient(
        String modelCode,
        String providerCode,
        String modelName,
        ChatClient chatClient
) {
}