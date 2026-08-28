package com.jsd.aird.ai.infrastructure;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AiExecutorConfiguration {

    @Bean(name = "aiStreamExecutor", destroyMethod = "close")
    ExecutorService aiStreamExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean(name = "ragRetrievalExecutor", destroyMethod = "shutdown")
    ExecutorService ragRetrievalExecutor(
            @Value("${app.ai.retrieval.executor-threads:8}") int threads,
            @Value("${app.ai.retrieval.queue-capacity:64}") int queueCapacity) {
        var size = Math.max(2, Math.min(32, threads));
        return new ThreadPoolExecutor(size, size, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(8, Math.min(1024, queueCapacity))),
                Thread.ofPlatform().name("rag-retrieval-", 0).factory(),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }
}
