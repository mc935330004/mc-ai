package org.example.ai.agent.business.panorama;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 项目全景模块的独立有界执行器，避免阻塞任务占满通用工作流线程池。
 */
@Configuration
public class PanoramaExecutionConfiguration {

    @Bean(name = "projectPanoramaModuleExecutor", destroyMethod = "shutdown")
    public ExecutorService projectPanoramaModuleExecutor(
            @Value("${ai.business.panorama.worker-threads:4}") int workerThreads,
            @Value("${ai.business.panorama.queue-capacity:64}") int queueCapacity) {
        if (workerThreads < 1 || workerThreads > 16
                || queueCapacity < 1 || queueCapacity > 1024) {
            throw new IllegalArgumentException("项目全景执行器配置不合法");
        }
        AtomicInteger sequence = new AtomicInteger();
        return new ThreadPoolExecutor(
                workerThreads,
                workerThreads,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread thread = new Thread(
                            runnable,
                            "project-panorama-" + sequence.incrementAndGet()
                    );
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }
}
