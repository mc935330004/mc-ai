package org.example.ai.agent.modelconfig.cache;

import lombok.extern.slf4j.Slf4j;
import org.example.ai.agent.modelconfig.client.ModelClientRegistry;
import org.example.ai.agent.modelconfig.event.ModelConfigChangedEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;

/**
 * 模型客户端缓存跨实例同步器。
 *
 * MySQL仍然是模型配置唯一真源；
 * Redis只广播模型编码，不保存API Key、运行配置或ChatClient。
 */
@Slf4j
@Component
public class ModelConfigCacheSynchronizer implements MessageListener {

    private final ModelClientRegistry modelClientRegistry;
    private final StringRedisTemplate redisTemplate;
    private final String channel;

    public ModelConfigCacheSynchronizer(
            ModelClientRegistry modelClientRegistry,
            StringRedisTemplate redisTemplate,
            @Value("${app.agent.model-config.cache-invalidation-channel}")
            String channel) {

        this.modelClientRegistry = modelClientRegistry;
        this.redisTemplate = redisTemplate;
        this.channel = channel;
    }

    /**
     * 当前实例修改模型配置并提交事务后，
     * 清理本机缓存并通知其他实例。
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT,fallbackExecution = true)
    public void handleModelConfigChanged(ModelConfigChangedEvent event) {

        if (event == null || !StringUtils.hasText(event.modelCode())) {
            return;
        }
        String modelCode = event.modelCode().trim();
        // 中文注释：先清理本机缓存，保证当前实例立即使用新配置。
        modelClientRegistry.invalidate(modelCode);

        publishSafely(modelCode);
    }

    /**
     * 接收其他实例发送的缓存失效通知。
     */
    @Override
    public void onMessage(Message message,byte[] pattern) {
        if (message == null || message.getBody() == null) {
            return;
        }
        String modelCode = new String(message.getBody(),StandardCharsets.UTF_8).trim();
        if (!StringUtils.hasText(modelCode)) {
            return;
        }
        // 中文注释：失效操作是幂等的，收到本机消息也可以重复执行。
        modelClientRegistry.invalidate(modelCode);
    }

    /**
     * Redis临时不可用时不回滚已经提交的数据库事务。
     *
     * 其他实例仍会通过本地60秒TTL重新读取数据库配置。
     */
    private void publishSafely(String modelCode) {
        try {
            redisTemplate.convertAndSend(
                    channel,
                    modelCode
            );
        } catch (RuntimeException exception) {
            log.warn(
                    "广播模型缓存失效消息失败，modelCode={}，errorType={}",
                    modelCode,
                    exception.getClass().getSimpleName()
            );
        }
    }
}