package org.example.ai.agent.modelconfig.cache;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * 模型配置缓存失效消息监听配置。
 */
@Configuration(proxyBeanMethods = false)
public class ModelConfigCacheRedisConfiguration {

    /**
     * 创建模型配置专用Redis消息监听容器。
     */
    @Bean
    public RedisMessageListenerContainer
    modelConfigCacheListenerContainer(
            RedisConnectionFactory connectionFactory,
            ModelConfigCacheSynchronizer synchronizer,
            @Value("${app.agent.model-config.cache-invalidation-channel}")
            String channel) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();

        container.setConnectionFactory(connectionFactory);
        // 中文注释：只订阅模型配置缓存失效频道。
        container.addMessageListener(
                synchronizer,
                new ChannelTopic(channel)
        );

        return container;
    }
}