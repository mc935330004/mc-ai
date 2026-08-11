package org.example.ai.agent.modelconfig.client;

import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import org.example.ai.agent.common.enums.ModelApiType;
import org.example.ai.agent.common.exception.BusinessException;
import org.example.ai.agent.modelconfig.model.ModelRuntimeConfig;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 根据运行配置创建模型客户端。
 *
 * Phase 1用于模型连接测试，
 * Phase 3继续在该工厂之上增加客户端缓存注册表。
 */
@Component
@RequiredArgsConstructor
public class ModelClientFactory {

    private final ObservationRegistry observationRegistry;

    public ChatClient create(ModelRuntimeConfig config) {
        ModelApiType apiType = ModelApiType.from(config.apiType());
        if (apiType != ModelApiType.OPENAI_COMPATIBLE) {
            throw new BusinessException(
                    400,
                    "当前模型接口类型尚未实现：" + config.apiType()
            );
        }

        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl(config.baseUrl())
                .apiKey(config.apiKey())
                .model(config.modelName())
                .temperature(config.temperature().doubleValue())
                .maxTokens(config.maxTokens())
                .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                /*
                 * 禁止SDK内部自动重试。
                 * Phase 4会在统一调用入口控制重试和备用模型切换，
                 * 避免SDK重试与业务故障转移叠加。
                 */
                .maxRetries(0)
                .build();

        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .options(options)
                .observationRegistry(observationRegistry)
                .build();

        return ChatClient.builder(chatModel).build();
    }
}