package com.chendev.ticketflow.infrastructure.adapter;

import com.chendev.ticketflow.common.exception.BizException;
import com.chendev.ticketflow.common.response.ResultCode;
import com.chendev.ticketflow.inventory.redis.RedisInventoryManager;
import com.chendev.ticketflow.order.port.InventoryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Redis-backed inventory adapter.
 *
 * Replaces InventoryAdapter (optimistic locking) as the active
 * InventoryPort implementation for Phase 2.
 *
 * Build order:
 *   Step 1-C (this file) : Lua execution + basic return code handling
 *   Step 1-D             : Cache miss → lazy-load from DB
 *   Step 1-E             : Redis connection failure → fallback to DB
 *   Step 1-F             : DB guard write after Redis success + compensation
 *   Step 1-G             : Reconciliation job (separate class)
 *   Step 1-H             : Add @Primary — this adapter becomes the active one
 *
 * NOT annotated with @Primary yet.
 * The existing InventoryAdapter remains active until Step 1-H,
 * keeping all Phase 1 integration tests green throughout the build.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisInventoryAdapter implements InventoryPort {

    private final RedisInventoryManager redisInventoryManager;

    /**
     * Deducts stock atomically via Lua script.
     *
     * Current state (Step 1-C): handles success (1) and insufficient stock (0).
     * Cache miss (-1) throws IllegalStateException — lazy-load added in Step 1-D.
     * Redis failure fallback added in Step 1-E.
     * DB guard write added in Step 1-F.
     */
    @Override
    public void deductStock(Long ticketTypeId, int quantity) {
        Long result = redisInventoryManager.deductStock(ticketTypeId, quantity);

        if (Long.valueOf(1L).equals(result)) {
            // Redis deducted atomically — no retry storms, no version conflicts.
            // DB guard write will be added in Step 1-F.
            log.info("[RedisInventory] Deducted: ticketTypeId={}, qty={}",
                    ticketTypeId, quantity);
            return;
        }

        if (Long.valueOf(0L).equals(result)) {
            throw BizException.of(ResultCode.INVENTORY_INSUFFICIENT,
                    "Tickets are sold out");
        }

        if (Long.valueOf(-1L).equals(result)) {
            // Cache miss: key not present in Redis.
            // This happens on first access or after a Redis restart.
            // Lazy-load from DB will replace this exception in Step 1-D.
            throw new IllegalStateException(
                    "[RedisInventory] Cache miss for ticketTypeId=" + ticketTypeId
                            + ". Lazy-load from DB not yet implemented — arriving in Step 1-D.");
        }

        // Defensive: Lua contract violation — should never reach here.
        throw new IllegalStateException(
                "[RedisInventory] Unexpected Lua return value: " + result
                        + " for ticketTypeId=" + ticketTypeId);
    }

    /**
     * Returns stock to Redis when an order is cancelled or expired.
     *
     * If the key is absent (Redis restarted), we log a warning and let the
     * reconciliation job rebuild Redis from DB. We do NOT fabricate a new key
     * with only the returned quantity — that would create a stale, incorrect value.
     *
     * DB release write will be added in Step 1-F.
     */
    @Override
    public void releaseStock(Long ticketTypeId, int quantity) {
        Long result = redisInventoryManager.releaseStock(ticketTypeId, quantity);

        if (Long.valueOf(1L).equals(result)) {
            log.info("[RedisInventory] Released: ticketTypeId={}, qty={}",
                    ticketTypeId, quantity);
            return;
        }

        if (Long.valueOf(0L).equals(result)) {
            // Key absent after Redis restart. Reconciliation job will sync from DB.
            // DB release write will be added in Step 1-F.
            log.warn("[RedisInventory] Release skipped — key absent in Redis: "
                            + "ticketTypeId={}. Reconciliation job will restore from DB.",
                    ticketTypeId);
        }
    }

    /**
     * Pre-flight stock check — a hint for early rejection, not an atomic guarantee.
     *
     * Returns false conservatively on cache miss: the caller will still attempt
     * deductStock, which will surface the cache miss explicitly (Step 1-D).
     *
     * NOTE: this is intentionally a TOCTOU (Time-of-Check-Time-of-Use) read.
     * It is NOT the correctness boundary. The Lua script in deductStock is.
     */
    @Override
    public boolean hasSufficientStock(Long ticketTypeId, int quantity) {
        return redisInventoryManager.getStock(ticketTypeId)
                .map(stock -> stock >= quantity)
                .orElse(false); // conservative: treat cache miss as "unknown → let deduct decide"
    }
}
