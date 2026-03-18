package com.chendev.ticketflow.infrastructure.adapter;

import com.chendev.ticketflow.common.exception.BizException;
import com.chendev.ticketflow.common.response.ResultCode;
import com.chendev.ticketflow.inventory.entity.Inventory;
import com.chendev.ticketflow.inventory.redis.RedisInventoryManager;
import com.chendev.ticketflow.inventory.repository.InventoryRepository;
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
 *   Step 1-C : Lua execution + basic return code handling
 *   Step 1-D : Cache miss → lazy-load from DB         ← current step
 *   Step 1-E : Redis connection failure → fallback to DB
 *   Step 1-F : DB guard write after Redis success + compensation
 *   Step 1-G : Reconciliation job (separate class)
 *   Step 1-H : Add @Primary — this adapter becomes the active one
 *
 * NOT annotated with @Primary yet.
 * InventoryAdapter (@Primary) remains active until Step 1-H.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisInventoryAdapter implements InventoryPort {

    private final RedisInventoryManager redisInventoryManager;
    private final InventoryRepository   inventoryRepository;

    // ─── InventoryPort implementation ────────────────────────────────────────

    /**
     * Deducts stock atomically via Lua script.
     *
     * Flow:
     *   1. Execute Lua → result 1 (success), 0 (insufficient), -1 (cache miss)
     *   2. On cache miss: load available_stock from DB → write to Redis → retry once
     *   3. Retry result is final — no further retry loop
     *
     * DB guard write and Redis failure fallback are added in Steps 1-F and 1-E.
     */
    @Override
    public void deductStock(Long ticketTypeId, int quantity) {
        Long result = redisInventoryManager.deductStock(ticketTypeId, quantity);

        if (Long.valueOf(-1L).equals(result)) {
            // Cache miss: Redis key is absent (first access or after Redis restart).
            // Load from DB, populate Redis, then retry exactly once.
            loadInventoryIntoRedis(ticketTypeId);
            result = redisInventoryManager.deductStock(ticketTypeId, quantity);
        }

        handleDeductResult(result, ticketTypeId);
    }

    /**
     * Returns stock to Redis when an order is cancelled or expired.
     *
     * If the key is absent after a Redis restart, we log and do nothing.
     * The reconciliation job (Step 1-G) will rebuild Redis from DB.
     *
     * DB release write added in Step 1-F.
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
            // Key absent after Redis restart.
            // Reconciliation job will restore Redis from DB.
            log.warn("[RedisInventory] Release skipped — key absent in Redis: "
                            + "ticketTypeId={}. Reconciliation job will restore from DB.",
                    ticketTypeId);
        }
    }

    /**
     * Pre-flight stock check — a hint for early rejection, not an atomic guarantee.
     *
     * Returns false conservatively on cache miss: the caller will still attempt
     * deductStock(), which handles the cache miss via lazy-load.
     *
     * This is intentionally a TOCTOU read. The Lua script in deductStock is
     * the correctness boundary, not this method.
     */
    @Override
    public boolean hasSufficientStock(Long ticketTypeId, int quantity) {
        return redisInventoryManager.getStock(ticketTypeId)
                .map(stock -> stock >= quantity)
                .orElse(false);
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    /**
     * Loads available_stock from DB and writes it to Redis.
     *
     * Called on cache miss. Two threads may call this concurrently — both will
     * write the same value, and the second SET overwrites the first harmlessly.
     * This "last writer wins" behaviour is safe because both reads see the same
     * committed DB value; there is no concurrent DB write at this point
     * (the Lua deduction has not happened yet for either thread).
     */
    private void loadInventoryIntoRedis(Long ticketTypeId) {
        Inventory inventory = inventoryRepository.findByTicketTypeId(ticketTypeId)
                .orElseThrow(() -> BizException.of(
                        ResultCode.TICKET_TYPE_NOT_FOUND,
                        "Inventory not found for ticketTypeId=" + ticketTypeId));

        redisInventoryManager.setStock(ticketTypeId, inventory.getAvailableStock());

        log.info("[RedisInventory] Cache miss — loaded from DB: ticketTypeId={}, stock={}",
                ticketTypeId, inventory.getAvailableStock());
    }

    /**
     * Interprets the Lua script return value after the initial call or retry.
     *
     * -1 after retry means loadInventoryIntoRedis() succeeded but the SET did
     * not persist (extremely rare: e.g. Redis evicted the key immediately).
     * Treat this as a transient Redis failure — Step 1-E will add the fallback.
     */
    private void handleDeductResult(Long result, Long ticketTypeId) {
        if (Long.valueOf(1L).equals(result)) {
            log.info("[RedisInventory] Deducted: ticketTypeId={}", ticketTypeId);
            return;
        }

        if (Long.valueOf(0L).equals(result)) {
            throw BizException.of(ResultCode.INVENTORY_INSUFFICIENT, "Tickets sold out");
        }

        if (Long.valueOf(-1L).equals(result)) {
            // Cache miss persisted after one retry — treat as transient Redis failure.
            // Redis failure fallback will be wired in Step 1-E.
            throw new IllegalStateException(
                    "[RedisInventory] Cache miss persisted after lazy-load retry: "
                            + "ticketTypeId=" + ticketTypeId
                            + ". Redis failure fallback not yet implemented — arriving in Step 1-E.");
        }

        throw new IllegalStateException(
                "[RedisInventory] Unexpected Lua return value: " + result
                        + " for ticketTypeId=" + ticketTypeId);
    }
}
