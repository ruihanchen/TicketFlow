package com.chendev.ticketflow.infrastructure.config;

import com.chendev.ticketflow.order.event.OrderCancelledEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * Kafka consumer error handling — retry policy and DLQ routing.
 *
 * Keeps retry/DLQ logic out of OrderCancelledConsumer so the consumer
 * only handles the happy path and known business cases.
 *
 * Retry: 1s → 4s → 16s (3 attempts). Covers transient Redis/DB hiccups
 * without holding the partition for too long.
 *
 * DLQ: exhausted messages go to order.cancelled.dlq. Recoverer logs
 * partition/offset so ops can replay individual messages after a fix.
 * DeserializationException is non-retryable — malformed bytes will never
 * deserialize; routing straight to DLQ avoids burning all three retries.
 */
@Slf4j
@Configuration
public class KafkaConsumerConfig {

    @Bean
    public CommonErrorHandler kafkaErrorHandler(
            KafkaTemplate<String, OrderCancelledEvent> kafkaTemplate) {

        DeadLetterPublishingRecoverer recoverer =
                new DeadLetterPublishingRecoverer(kafkaTemplate,
                        (record, ex) -> {
                            log.error("[DLQ] Routing to DLQ after exhausted retries. " +
                                            "topic={}, partition={}, offset={}, error={}",
                                    record.topic(), record.partition(),
                                    record.offset(), ex.getMessage());
                            return null; // Spring default: {topic}.dlq, same partition
                        });

        ExponentialBackOffWithMaxRetries backOff =
                new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(1_000L);
        backOff.setMultiplier(4.0);
        backOff.setMaxInterval(30_000L);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(
                org.springframework.kafka.support.serializer.DeserializationException.class,
                com.fasterxml.jackson.core.JsonProcessingException.class
        );

        return handler;
    }
}
