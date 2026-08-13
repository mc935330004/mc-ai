package org.example.ai.agent.modules.knowledgebase.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 知识库流式查询的资源与超时配置。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.knowledge-query")
public class KnowledgeQueryProperties {

    /**
     * 单次SSE连接最长保留时间，单位毫秒。
     */
    private long timeoutMs = 180_000L;

    /**
     * 知识库查询线程池核心线程数。
     */
    private int executorCoreSize = 2;

    /**
     * 知识库查询线程池最大线程数。
     */
    private int executorMaxSize = 8;

    /**
     * 等待执行的知识库查询数量上限。
     */
    private int executorQueueCapacity = 100;

    /**
     * 应用关闭时等待查询结束的秒数。
     */
    private int shutdownAwaitSeconds = 30;

    /**
     * 启动时校验线程池和超时配置，避免错误配置进入运行期。
     */
    @PostConstruct
    public void validate() {
        if (timeoutMs <= 0) {
            throw new IllegalStateException(
                    "app.knowledge-query.timeout-ms必须大于0"
            );
        }
        if (executorCoreSize <= 0
                || executorMaxSize < executorCoreSize) {
            throw new IllegalStateException(
                    "知识库查询线程池大小配置不合法"
            );
        }
        if (executorQueueCapacity < 0) {
            throw new IllegalStateException(
                    "知识库查询线程池队列容量不能小于0"
            );
        }
        if (shutdownAwaitSeconds < 0) {
            throw new IllegalStateException(
                    "知识库查询线程池关闭等待时间不能小于0"
            );
        }
    }
}
