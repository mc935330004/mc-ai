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
}