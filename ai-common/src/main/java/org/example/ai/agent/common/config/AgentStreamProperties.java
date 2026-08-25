package org.example.ai.agent.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agent SSE流配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.agent.stream")
public class AgentStreamProperties {

    /**
     * SSE最大连接时间，单位毫秒。
     */
    private long timeoutMs = 180_000L;

    /**
     * Agent后台执行线程池核心线程数。
     */
    private int executorCoreSize = 4;

    /**
     * Agent后台执行线程池最大线程数。
     */
    private int executorMaxSize = 16;

    /**
     * Agent后台执行队列容量。
     */
    private int executorQueueCapacity = 200;
}