package org.example.ai.agent.common.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Agent 后台任务线程池配置。
 */
@Configuration
@RequiredArgsConstructor
public class AgentAsyncConfiguration {

    private final AgentStreamProperties properties;

    /**
     * 创建 Agent 聊天专用线程池。
     *
     * 避免使用 ForkJoinPool.commonPool，
     * 防止多个异步业务互相影响。
     */
    @Bean("agentChatExecutor")
    public Executor agentChatExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        executor.setCorePoolSize( properties.getExecutorCoreSize());

        executor.setMaxPoolSize(properties.getExecutorMaxSize());

        executor.setQueueCapacity(properties.getExecutorQueueCapacity());

        executor.setThreadNamePrefix("agent-chat-");

        /*
         * 应用关闭时等待正在执行的回答完成。
         */
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);

        executor.initialize();
        return executor;
    }
}