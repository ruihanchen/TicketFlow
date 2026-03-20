package com.chendev.ticketflow.infrastructure.config;

import com.chendev.ticketflow.order.event.OrderCancelledEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * Kafka consumer error handling and listener container configuration.
 *
 * Defines the container factory explicitly to set AckMode.MANUAL_IMMEDIATE,
 * which is required when listener methods declare an Acknowledgment parameter.
 * Without this, Spring Kafka cannot inject Acknowledgment and throws
 * ListenerExecutionFailedException: invokeHandler Failed at startup.
 *
 * Retry: 1s → 4s → 16s (3 attempts) covers transient Redis/DB hiccups.
 * DLQ: exhausted messages go to order.cancelled.dlq for ops investigation.
 * DeserializationException is non-retryable — malformed bytes never deserialize.
 */
@Slf4j
@Configuration
public class KafkaConsumerConfig {

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory,
            CommonErrorHandler kafkaErrorHandler) {

        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        // Required: Acknowledgment parameter in @KafkaListener methods only works
        // when AckMode is MANUAL or MANUAL_IMMEDIATE. Without this, Spring Kafka
        // fails to resolve the Acknowledgment argument and throws invokeHandler Failed.
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }

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
