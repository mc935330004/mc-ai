package org.example.ai.agent.modules.knowledgebase.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 知识库查询专用线程池配置。
 */
@Configuration
@RequiredArgsConstructor
public class KnowledgeQueryAsyncConfiguration {

    private final KnowledgeQueryProperties properties;

    /**
     * 创建有界线程池，防止知识库查询占用公共线程池。
     */
    @Bean("knowledgeQueryExecutor")
    public KnowledgeQueryTaskExecutor knowledgeQueryExecutor() {
        KnowledgeQueryTaskExecutor executor =
                new KnowledgeQueryTaskExecutor();
        executor.setCorePoolSize(properties.getExecutorCoreSize());
        executor.setMaxPoolSize(properties.getExecutorMaxSize());
        executor.setQueueCapacity(properties.getExecutorQueueCapacity());
        executor.setThreadNamePrefix("knowledge-query-");
        executor.setRejectedExecutionHandler(
                new ThreadPoolExecutor.AbortPolicy()
        );
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(
                properties.getShutdownAwaitSeconds()
        );
        executor.initialize();
        return executor;
    }
}
