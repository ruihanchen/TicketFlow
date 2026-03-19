package com.chendev.ticketflow.order.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain event published when an order is cancelled, regardless of reason
 * (user-initiated, system timeout, or payment failure).
 *
 * Published to Spring's ApplicationEventPublisher inside the @Transactional
 * cancel operation. OrderCancelledKafkaPublisher forwards it to Kafka
 * AFTER_COMMIT so the DB state is durable before the consumer sees the message.
 *
 * Why a Java record?
 * Events are value objects — immutable, identity defined by content.
 * Records enforce this by construction and eliminate boilerplate.
 *
 * Fields:
 *   messageId    — UUID for consumer idempotency check (SETNX key).
 *   orderNo      — business identifier, used as Kafka partition key.
 *   ticketTypeId — tells the consumer which inventory row to restore.
 *   quantity     — units to return.
 *   reason       — written to audit log for observability.
 *   occurredAt   — event timestamp for ordering and debugging.
 */
public record OrderCancelledEvent(
        String  messageId,
        String  orderNo,
        Long    ticketTypeId,
        int     quantity,
        String  reason,
        Instant occurredAt
) {
    public static OrderCancelledEvent of(
            String orderNo,
            Long   ticketTypeId,
            int    quantity,
            String reason) {
        return new OrderCancelledEvent(
                UUID.randomUUID().toString(),
                orderNo,
                ticketTypeId,
                quantity,
                reason,
                Instant.now()
        );
    }
}