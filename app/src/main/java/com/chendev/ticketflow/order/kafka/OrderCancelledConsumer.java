package com.chendev.ticketflow.order.kafka;

import com.chendev.ticketflow.inventory.redis.RedisInventoryManager;
import com.chendev.ticketflow.order.event.OrderCancelledEvent;
import com.chendev.ticketflow.order.port.InventoryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Restores inventory when an order is cancelled.
 *
 * Redis-first via release_stock_idempotent.lua (atomic check + INCRBY + SETEX).
 * Covers concurrent redelivery during rebalance — non-atomic would let two
 * instances both pass the idempotency check and double-restore.
 *
 * DUPLICATE branch deliberately skips the DB write. If we re-called
 * releaseStock() and the previous attempt's DB write had succeeded, we'd
 * over-count inventory — worse than the alternative. Accepting rare under-sell;
 * InventoryReconciler corrects Redis > DB drift within its next cycle (~5 min).
 *
 * TODO (Phase 3): replace AFTER_COMMIT + Reconciler safety net with
 * Transactional Outbox to eliminate the under-sell window entirely.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderCancelledConsumer {

    private final RedisInventoryManager redisInventoryManager;
    private final InventoryPort         inventoryPort;

    @KafkaListener(
            topics  = "${ticketflow.kafka.topics.order-cancelled:order.cancelled}",
            groupId = "ticketflow-order-group"
    )
    public void onMessage(OrderCancelledEvent event, Acknowledgment ack) {
        log.info("[Consumer] Received OrderCancelledEvent: orderNo={}, " +
                        "ticketTypeId={}, qty={}, messageId={}",
                event.orderNo(), event.ticketTypeId(),
                event.quantity(), event.messageId());

        try {
            processEvent(event, ack);
        } catch (Exception e) {
            // Log-and-rethrow: adds business context before the exception reaches
            // DefaultErrorHandler. Without this, ELK logs contain only partition/offset;
            // engineers must manually reverse-map offsets to business order numbers.
            // With this, searching "orderNo=TF123456" surfaces the failure instantly.
            //
            // CRITICAL: must rethrow. Swallowing the exception here would prevent
            // DefaultErrorHandler from triggering retry/DLQ — messages silently disappear.
            log.error("[Consumer] Failed to process OrderCancelledEvent. " +
                            "orderNo={}, messageId={}, error={}. " +
                            "Propagating to Kafka ErrorHandler for backoff/DLQ routing.",
                    event.orderNo(), event.messageId(), e.getMessage());
            throw e;
        }
    }

    private void processEvent(OrderCancelledEvent event, Acknowledgment ack) {
        // Step 1: atomic Redis operation — idempotency check + inventory release.
        // RedisSystemException / QueryTimeoutException propagate to the outer catch.
        // Lettuce command timeout (2000ms, configured in application.yml) ensures
        // this call fails fast rather than blocking the Kafka poll thread indefinitely.
        String result = redisInventoryManager.releaseStockIdempotent(
                event.messageId(),
                event.ticketTypeId(),
                event.quantity()
        );

        switch (result) {
            case "OK" -> {
                // First-time processing: Redis inventory restored.
                // Sync DB to keep Redis and DB consistent.
                log.info("[Consumer] Redis inventory restored (first-time): " +
                                "orderNo={}, ticketTypeId={}, qty={}",
                        event.orderNo(), event.ticketTypeId(), event.quantity());

                // DB exception propagates to outer catch → log-and-rethrow → retry/DLQ.
                inventoryPort.releaseStock(event.ticketTypeId(), event.quantity());

                ack.acknowledge();
                log.info("[Consumer] ACK sent: orderNo={}, messageId={}",
                        event.orderNo(), event.messageId());
            }

            case "DUPLICATE" -> {
                // messageId already processed by a previous attempt.
                //
                // KAFKA_DUPLICATE_SKIP — known trade-off (see class Javadoc).
                // Do NOT call inventoryPort.releaseStock() here.
                log.warn("[KAFKA_DUPLICATE_SKIP] Duplicate message skipped. " +
                                "If DB was not updated in the previous attempt, " +
                                "stock for ticketTypeId={} may be understated until " +
                                "InventoryReconciler corrects the drift. " +
                                "orderNo={}, messageId={}",
                        event.ticketTypeId(), event.orderNo(), event.messageId());

                ack.acknowledge();
            }

            case "CACHE_MISS" -> {
                // Inventory key absent in Redis. Update DB directly.
                // Reconciler will rebuild Redis cache on next cycle.
                log.warn("[Consumer] Redis cache miss for ticketTypeId={}. " +
                                "Restoring DB directly. Reconciler will rebuild Redis cache. " +
                                "orderNo={}, messageId={}",
                        event.ticketTypeId(), event.orderNo(), event.messageId());

                // DB exception propagates to outer catch → log-and-rethrow → retry/DLQ.
                inventoryPort.releaseStock(event.ticketTypeId(), event.quantity());

                ack.acknowledge();
                log.info("[Consumer] ACK sent (cache-miss path): orderNo={}, messageId={}",
                        event.orderNo(), event.messageId());
            }

            default -> {
                // Unknown Lua return code — defensive catch.
                // Throw so the error handler routes to DLQ.
                log.error("[Consumer] Unknown Lua result '{}' for orderNo={}, messageId={}. " +
                                "Routing to DLQ.",
                        result, event.orderNo(), event.messageId());
                throw new IllegalStateException(
                        "Unknown Lua script result: " + result +
                                " for messageId=" + event.messageId());
            }
        }
    }
}
