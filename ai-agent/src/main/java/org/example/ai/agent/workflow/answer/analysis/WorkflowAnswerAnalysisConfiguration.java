package org.example.ai.agent.workflow.answer.analysis;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI 报告分析链路专用线程池。
 *
 * 与 agentChatExecutor 相互独立：
 * 1. workflowAnswerChunkExecutor：分块并发消费专用，避免抢占 Agent 聊天线程池；
 * 2. workflowAnswerAnalysisExecutor：超时保护专用，提交分析任务并等待超时结果。
 *
 * 两个池子职责分离，避免"外层等待内层任务"造成的线程饥饿。
 */
@Configuration
public class WorkflowAnswerAnalysisConfiguration {

    /**
     * 分块并发消费线程池。
     *
     * 大小与配置的并发数一致，限制同时调用模型的请求数量。
     */
    @Bean(name = "workflowAnswerChunkExecutor", destroyMethod = "shutdown")
    public ExecutorService workflowAnswerChunkExecutor(
            WorkflowAnswerAnalysisProperties properties) {
        return Executors.newFixedThreadPool(
                properties.getConcurrency(),
                new AnalysisThreadFactory("workflow-answer-chunk-")
        );
    }

    /**
     * 分析超时保护线程池。
     *
     * 固定2线程：提交一次完整分析任务后阻塞等待，
     * 超过配置秒数即取消并降级为基础报告。
     */
    @Bean(name = "workflowAnswerAnalysisExecutor", destroyMethod = "shutdown")
    public ExecutorService workflowAnswerAnalysisExecutor() {
        return Executors.newFixedThreadPool(
                2,
                new AnalysisThreadFactory("workflow-answer-analysis-")
        );
    }

    /**
     * 带统一命名前缀的线程工厂，便于日志和线程栈定位。
     */
    private static final class AnalysisThreadFactory implements ThreadFactory {

        private final String prefix;

        private final AtomicInteger sequence = new AtomicInteger(1);

        private AnalysisThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, prefix + sequence.getAndIncrement());
            thread.setDaemon(false);
            return thread;
        }
    }
}
