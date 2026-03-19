package com.chendev.ticketflow.order.kafka;

import com.chendev.ticketflow.order.event.OrderCancelledEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Forwards OrderCancelledEvent to Kafka after the DB transaction commits.
 *
 * Why @TransactionalEventListener(AFTER_COMMIT)?
 *   Sending inside @Transactional risks ghost restores: if the DB rolls back
 *   after the message is sent, the consumer restores inventory for an order
 *   that was never cancelled. AFTER_COMMIT ensures DB state is durable first.
 *
 * Why @Async("kafkaEventExecutor")?
 *   @TransactionalEventListener defaults to executing on the committing thread —
 *   the same Tomcat thread serving the HTTP request. Binding Kafka I/O to that
 *   thread degrades response times under concurrent cancellations.
 *   @Async moves the call to a dedicated executor, releasing the business thread
 *   immediately after commit.
 *
 * Resilience: uses a dedicated bounded ThreadPoolTaskExecutor (kafkaEventExecutor,
 * defined in AsyncConfig) with CallerRunsPolicy. Queue capacity=1000 prevents
 * unbounded memory accumulation during Kafka outages. CallerRunsPolicy provides
 * natural backpressure when the queue fills — the submitting thread executes the
 * task itself instead of dropping it or throwing an exception.
 *
 * Known trade-off (AFTER_COMMIT without Outbox):
 *   If the JVM crashes between DB commit and Kafka send, the message is lost.
 *   InventoryReconciler CANNOT fix this — it only syncs Redis ↔ DB, not
 *   cancelled orders ↔ inventory. Permanent Under-selling results.
 *   The bounded executor and CallerRunsPolicy mitigate Kafka-slow scenarios
 *   but cannot cover JVM crash. The Outbox pattern eliminates this window entirely
 *   and is the correct production-grade solution.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCancelledKafkaPublisher {

    private final KafkaTemplate<String, OrderCancelledEvent> kafkaTemplate;

    @Value("${ticketflow.kafka.topics.order-cancelled:order.cancelled}")
    private String topic;

    @Async("kafkaEventExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(OrderCancelledEvent event) {
        log.info("[Kafka] Publishing OrderCancelledEvent: orderNo={}, ticketTypeId={}, " +
                        "qty={}, messageId={}",
                event.orderNo(), event.ticketTypeId(), event.quantity(), event.messageId());

        kafkaTemplate.send(topic, event.orderNo(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        // ⚠️ PERMANENT STOCK LEAKAGE — requires manual intervention.
                        //
                        // The order is CANCELLED in DB, but this Kafka message is lost.
                        // The consumer will never restore inventory for this cancellation.
                        //
                        // Why InventoryReconciler CANNOT fix this:
                        //   Reconciler compares Redis available_stock vs DB available_stock.
                        //   After this failure both are identical (e.g. both = 99).
                        //   Reconciler sees no drift and does nothing.
                        //   The cancelled ticket is permanently leaked (Under-selling).
                        //
                        // Resolution path:
                        //   Search logs for FATAL_KAFKA_DROP to find affected orders.
                        //   Manually run: UPDATE inventories
                        //                 SET available_stock = available_stock + {qty}
                        //                 WHERE ticket_type_id = {ticketTypeId};
                        //   In production this log must trigger a PagerDuty alert.
                        log.error("[FATAL_KAFKA_DROP] Kafka message lost after DB commit. " +
                                        "Stock permanently leaked — InventoryReconciler cannot recover. " +
                                        "Manual DB compensation required. " +
                                        "ticketTypeId={}, qty={}, orderNo={}, messageId={}, error={}",
                                event.ticketTypeId(), event.quantity(),
                                event.orderNo(), event.messageId(), ex.getMessage());
                    } else {
                        log.info("[Kafka] OrderCancelledEvent delivered: orderNo={}, " +
                                        "partition={}, offset={}",
                                event.orderNo(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
