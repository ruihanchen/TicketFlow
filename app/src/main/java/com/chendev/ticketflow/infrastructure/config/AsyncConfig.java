package com.chendev.ticketflow.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Async executor configuration for Kafka event publishing.
 *
 * Why not use Spring Boot's default executor?
 * Spring Boot's default SimpleAsyncTaskExecutor (and the auto-configured
 * ThreadPoolTaskExecutor) uses an unbounded queue (Integer.MAX_VALUE capacity).
 * If Kafka is unavailable for several minutes, OrderCancelledEvents accumulate
 * in the queue without bound — a direct OOM vector that takes down the entire JVM.
 *
 * This configuration provides three defences:
 *
 *   1. Bounded queue (1000): limits in-memory event accumulation during Kafka outages.
 *      At ~200 bytes per event, 1000 events ≈ 200 KB — negligible heap cost.
 *
 *   2. CallerRunsPolicy: when the queue is full, the submitting thread (Tomcat or
 *      the timeout job) executes the task itself instead of throwing
 *      RejectedExecutionException. Provides natural backpressure — slowing the
 *      producer rather than silently dropping events or crashing the executor.
 *
 *   3. Named threads (KafkaEvent-N): thread dumps clearly identify Kafka publish
 *      activity, making production incident diagnosis faster.
 *
 * Sizing rationale:
 *   corePoolSize=2:    handles normal throughput with minimal idle threads.
 *   maxPoolSize=10:    bursts during flash sales without unbounded growth.
 *   queueCapacity=1000: absorbs transient Kafka hiccups (seconds, not minutes).
 *   For sustained Kafka outages (minutes+), CallerRunsPolicy kicks in and the
 *   Outbox pattern is the correct long-term solution.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "kafkaEventExecutor")
    public Executor kafkaEventExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("KafkaEvent-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
