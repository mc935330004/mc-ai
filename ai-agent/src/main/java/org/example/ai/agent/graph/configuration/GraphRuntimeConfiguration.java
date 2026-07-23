package org.example.ai.agent.graph.configuration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GraphSpec运行线程池。
 */
@Configuration
public class GraphRuntimeConfiguration {

    @Bean(name = "graphRuntimeExecutor",destroyMethod = "shutdown" )
    public ExecutorService graphRuntimeExecutor( @Value( "${ai.graph.runtime.worker-threads:8}" ) int workerThreads) {

        int threadCount =
                Math.max(
                        2,
                        Math.min(workerThreads, 32)
                );

        AtomicInteger sequence =
                new AtomicInteger(1);

        return Executors.newFixedThreadPool(
                threadCount,
                runnable -> {
                    Thread thread =
                            new Thread(runnable);

                    thread.setName(
                            "graph-runtime-" +
                                    sequence.getAndIncrement()
                    );

                    thread.setDaemon(true);

                    return thread;
                }
        );
    }

    @Bean(name = "graphForEachExecutor",destroyMethod = "shutdown")
    public ExecutorService graphForEachExecutor(
            @Value("${ai.graph.runtime.foreach-worker-threads:5}" )int workerThreads) {

        /*
         * 产品规则限制最多5个项目，
         * 所以线程数也不允许超过5。
         */
        int threadCount =Math.max(1, Math.min(workerThreads, 5));

        AtomicInteger sequence =new AtomicInteger(1);

        return Executors.newFixedThreadPool(
                threadCount,
                runnable -> {
                    Thread thread =
                            new Thread(runnable);
                    thread.setName("graph-foreach-" + sequence.getAndIncrement() );
                    thread.setDaemon(true);
                    return thread;
                }
        );
    }
    /**
     * 第二层FOREACH专用线程池。
     *
     * 外层项目任务可能正在等待内层详情任务，
     * 因此内外层不能共用同一个固定线程池。
     *
     * 全局最多5个内层任务并发，
     * 不会因为5个项目各自循环而放大到25个并发请求。
     */
    @Bean(name = "graphNestedForEachExecutor",destroyMethod = "shutdown")
    public ExecutorService graphNestedForEachExecutor(
            @Value("${ai.graph.runtime.foreach-worker-threads:5}")int workerThreads) {
        int threadCount =Math.max( 1,Math.min( workerThreads,5));

        AtomicInteger sequence =new AtomicInteger(1);

        return Executors.newFixedThreadPool(
                threadCount,
                runnable -> {
                    Thread thread =new Thread(runnable);
                    thread.setName("graph-nested-foreach-"+ sequence.getAndIncrement());
                    thread.setDaemon(true);
                    return thread;
                }
        );
    }
}