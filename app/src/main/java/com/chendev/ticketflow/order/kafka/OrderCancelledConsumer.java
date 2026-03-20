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
 * Idempotency via release_stock_idempotent.lua (atomic SETNX + INCRBY):
 *   - OK: Redis already incremented by Lua; only DB needs updating.
 *     Calls releaseStockDbOnly() — not releaseStock() — to avoid double-INCRBY.
 *   - DUPLICATE: Redis is guaranteed correct (Lua ran). DB may or may not
 *     have been updated. We skip DB to avoid double-restore if it did succeed.
 *     Accepted trade-off: rare under-selling until Reconciler corrects drift.
 *   - CACHE_MISS: inventory key absent; Lua couldn't increment Redis.
 *     Call releaseStockDbOnly() — Redis will be rebuilt by Reconciler.
 *
 * TODO (Phase 3): Outbox pattern eliminates the DUPLICATE under-sell window.
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
            // Log-and-rethrow: surfaces orderNo + messageId in ELK before
            // DefaultErrorHandler applies backoff (1s → 4s → 16s) and DLQ routing.
            log.error("[Consumer] Processing failed. orderNo={}, messageId={}, error={}. " +
                            "Propagating to Kafka ErrorHandler.",
                    event.orderNo(), event.messageId(), e.getMessage());
            throw e;
        }
    }

    private void processEvent(OrderCancelledEvent event, Acknowledgment ack) {
        String result = redisInventoryManager.releaseStockIdempotent(
                event.messageId(),
                event.ticketTypeId(),
                event.quantity()
        );

        switch (result) {
            case "OK" -> {
                // Lua already did Redis INCRBY atomically.
                // Call releaseStockDbOnly() — NOT releaseStock() — to avoid double-INCRBY.
                log.info("[Consumer] Lua restored Redis. Syncing DB: " +
                                "orderNo={}, ticketTypeId={}, qty={}",
                        event.orderNo(), event.ticketTypeId(), event.quantity());

                inventoryPort.releaseStockDbOnly(event.ticketTypeId(), event.quantity());
                ack.acknowledge();

                log.info("[Consumer] ACK sent: orderNo={}, messageId={}",
                        event.orderNo(), event.messageId());
            }

            case "DUPLICATE" -> {
                // Redis is guaranteed correct (Lua ran on first delivery).
                // DB may or may not be updated — we skip to avoid double-restore.
                //
                // KAFKA_DUPLICATE_SKIP — accepted trade-off:
                // If previous attempt's DB write failed, inventory is temporarily
                // understated until InventoryReconciler corrects Redis > DB drift (~5 min).
                log.warn("[KAFKA_DUPLICATE_SKIP] Redis guaranteed correct; skipping DB. " +
                                "If previous DB write failed, stock for ticketTypeId={} is " +
                                "understated until Reconciler runs. orderNo={}, messageId={}",
                        event.ticketTypeId(), event.orderNo(), event.messageId());

                ack.acknowledge();
            }

            case "CACHE_MISS" -> {
                // Inventory key absent — Lua couldn't increment Redis.
                // Update DB directly; Reconciler rebuilds Redis on next cycle.
                log.warn("[Consumer] Redis cache miss for ticketTypeId={}. " +
                                "Updating DB directly; Reconciler will rebuild Redis. " +
                                "orderNo={}, messageId={}",
                        event.ticketTypeId(), event.orderNo(), event.messageId());

                inventoryPort.releaseStockDbOnly(event.ticketTypeId(), event.quantity());
                ack.acknowledge();

                log.info("[Consumer] ACK sent (cache-miss path): orderNo={}, messageId={}",
                        event.orderNo(), event.messageId());
            }

            default -> {
                log.error("[Consumer] Unknown Lua result '{}'. Routing to DLQ. " +
                                "orderNo={}, messageId={}",
                        result, event.orderNo(), event.messageId());
                throw new IllegalStateException(
                        "Unknown Lua result: " + result + " for messageId=" + event.messageId());
            }
        }
    }
}
