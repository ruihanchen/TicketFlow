package com.chendev.ticketflow.infrastructure.adapter;

import com.chendev.ticketflow.common.exception.BizException;
import com.chendev.ticketflow.common.response.ResultCode;
import com.chendev.ticketflow.inventory.entity.Inventory;
import com.chendev.ticketflow.inventory.redis.RedisInventoryManager;
import com.chendev.ticketflow.inventory.repository.InventoryRepository;
import com.chendev.ticketflow.order.port.InventoryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.stereotype.Component;

/**
 * Redis-backed inventory adapter.
 *
 * Replaces InventoryAdapter (optimistic locking) as the active
 * InventoryPort implementation for Phase 2.
 *
 * Build order:
 *   Step 1-C : Lua execution + basic return code handling
 *   Step 1-D : Cache miss → lazy-load from DB
 *   Step 1-E : Redis connection failure → fallback to DB   ← current step
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

    // Fallback to Phase 1 optimistic-locking adapter when Redis is unavailable.
    // Declared as a field dependency rather than looked up at runtime so that
    // Spring wires it at startup — no ServiceLocator pattern needed.
    //
    // Why delegate to InventoryAdapter instead of calling InventoryRepository directly?
    // InventoryAdapter already encapsulates optimistic lock retry and exception mapping.
    // Duplicating that logic here would create two sources of truth for the same behaviour.
    private final InventoryAdapter dbFallbackAdapter;

    // ─── InventoryPort implementation ────────────────────────────────────────

    /**
     * Deducts stock atomically via Lua script.
     *
     * Happy path  : Lua → (cache miss → lazy-load → retry) → success
     * Failure path: any RedisException → WARN log → delegate to DB adapter
     *
     * DB guard write is added in Step 1-F.
     */
    @Override
    public void deductStock(Long ticketTypeId, int quantity) {
        try {
            Long result = redisInventoryManager.deductStock(ticketTypeId, quantity);

            if (Long.valueOf(-1L).equals(result)) {
                loadInventoryIntoRedis(ticketTypeId);
                result = redisInventoryManager.deductStock(ticketTypeId, quantity);
            }

            handleDeductResult(result, ticketTypeId);

        } catch (BizException e) {
            // Business exceptions (INSUFFICIENT_STOCK, TICKET_TYPE_NOT_FOUND) must
            // propagate to the caller unchanged — do NOT fall back on business errors.
            throw e;
        } catch (Exception e) {
            // Redis infrastructure failure (connection refused, timeout, etc.).
            // Degrade gracefully: the system is slower but fully functional.
            log.warn("[RedisInventory] Redis unavailable, falling back to DB adapter: "
                    + "ticketTypeId={}, reason={}", ticketTypeId, e.getMessage());
            dbFallbackAdapter.deductStock(ticketTypeId, quantity);
        }
    }

    /**
     * Returns stock to Redis when an order is cancelled or expired.
     *
     * On Redis failure: WARN log only.
     * The reconciliation job (Step 1-G) will detect and correct the drift.
     *
     * DB release write added in Step 1-F.
     */
    @Override
    public void releaseStock(Long ticketTypeId, int quantity) {
        try {
            Long result = redisInventoryManager.releaseStock(ticketTypeId, quantity);

            if (Long.valueOf(1L).equals(result)) {
                log.info("[RedisInventory] Released: ticketTypeId={}, qty={}",
                        ticketTypeId, quantity);
                return;
            }

            if (Long.valueOf(0L).equals(result)) {
                log.warn("[RedisInventory] Release skipped — key absent in Redis: "
                                + "ticketTypeId={}. Reconciliation job will restore from DB.",
                        ticketTypeId);
            }

        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            // Redis unavailable during release.
            // Fall back to DB adapter to ensure the DB stock is restored.
            // Redis drift will be corrected by the reconciliation job.
            log.warn("[RedisInventory] Redis unavailable during releaseStock, "
                            + "falling back to DB adapter: ticketTypeId={}, reason={}",
                    ticketTypeId, e.getMessage());
            dbFallbackAdapter.releaseStock(ticketTypeId, quantity);
        }
    }

    /**
     * Pre-flight stock check.
     *
     * On Redis failure: fall back to DB check conservatively.
     * Returns false on any exception so that deductStock() makes the final call.
     */
    @Override
    public boolean hasSufficientStock(Long ticketTypeId, int quantity) {
        try {
            return redisInventoryManager.getStock(ticketTypeId)
                    .map(stock -> stock >= quantity)
                    .orElse(false);
        } catch (Exception e) {
            log.warn("[RedisInventory] Redis unavailable for stock check, "
                    + "returning false conservatively: ticketTypeId={}", ticketTypeId);
            return false;
        }
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    /**
     * Loads available_stock from DB and writes it to Redis.
     *
     * Called on cache miss. Concurrent calls are safe — both threads write
     * the same committed DB value; last-writer-wins is harmless here.
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
     * Interprets the Lua return value after the initial call or cache-miss retry.
     *
     * A -1 after retry means the key vanished between SET and GET — extremely
     * rare (e.g. maxmemory eviction). The outer catch block in deductStock()
     * will NOT catch this because it is thrown as a plain RuntimeException,
     * not a RedisException. It surfaces as an internal error so the operator
     * can investigate rather than silently degrading.
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
            // Key evicted immediately after SET — treat as hard failure, not fallback.
            // If this appears in logs, check Redis maxmemory-policy configuration.
            throw new IllegalStateException(
                    "[RedisInventory] Key evicted immediately after lazy-load: "
                            + "ticketTypeId=" + ticketTypeId
                            + ". Check Redis maxmemory-policy.");
        }

        throw new IllegalStateException(
                "[RedisInventory] Unexpected Lua return value: " + result
                        + " for ticketTypeId=" + ticketTypeId);
    }
}
