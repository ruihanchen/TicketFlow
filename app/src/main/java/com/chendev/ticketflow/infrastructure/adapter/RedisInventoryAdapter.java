package com.chendev.ticketflow.infrastructure.adapter;

import com.chendev.ticketflow.common.exception.BizException;
import com.chendev.ticketflow.common.response.ResultCode;
import com.chendev.ticketflow.inventory.entity.Inventory;
import com.chendev.ticketflow.inventory.redis.RedisInventoryManager;
import com.chendev.ticketflow.inventory.repository.InventoryRepository;
import com.chendev.ticketflow.order.port.InventoryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Redis-backed implementation of InventoryPort.
 *
 * Deductions are atomic via Lua script — no optimistic lock retries,
 * no retry storms under flash sale load. DB is kept in sync via a
 * conditional guard write after every successful Redis deduction.
 *
 * Degradation path: any RedisException falls back to InventoryAdapter
 * (optimistic locking). The system remains fully functional at reduced
 * throughput — no errors surface to the caller.
 */
@Slf4j
@Primary
@Component
@RequiredArgsConstructor
public class RedisInventoryAdapter implements InventoryPort {

    private final RedisInventoryManager redisInventoryManager;
    private final InventoryRepository   inventoryRepository;

    // Fallback to DB-backed adapter when Redis is unavailable.
    // Delegates to InventoryAdapter rather than calling InventoryRepository
    // directly because InventoryAdapter already owns optimistic lock retry
    // and exception mapping. Duplicating that logic here would create two
    // sources of truth for the same behaviour.
    private final InventoryAdapter dbFallbackAdapter;

    // ─── InventoryPort implementation ────────────────────────────────────────

    /**
     * Deducts stock atomically via Lua script, then persists to DB.
     *
     * REQUIRES_NEW: this transaction commits independently from the outer
     * OrderService transaction, matching the behaviour of InventoryAdapter.
     * Redis runs outside any JPA transaction scope — this annotation only
     * governs the DB guard write that follows.
     *
     * noRollbackFor = BizException: business rejections (sold out, not found)
     * must not poison the outer transaction's rollback state.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW,
            noRollbackFor = BizException.class)
    public void deductStock(Long ticketTypeId, int quantity) {
        try {
            Long result = redisInventoryManager.deductStock(ticketTypeId, quantity);

            if (Long.valueOf(-1L).equals(result)) {
                loadInventoryIntoRedis(ticketTypeId);
                result = redisInventoryManager.deductStock(ticketTypeId, quantity);
            }

            handleDeductResult(result, ticketTypeId);
            persistDeductToDB(ticketTypeId, quantity);

        } catch (BizException e) {
            // Business rejection — propagate unchanged.
            // No Redis compensation needed: Redis correctly reflects the state.
            throw e;
        } catch (Exception e) {
            log.warn("[RedisInventory] Redis unavailable, falling back to DB adapter: "
                    + "ticketTypeId={}, reason={}", ticketTypeId, e.getMessage());
            dbFallbackAdapter.deductStock(ticketTypeId, quantity);
        }
    }

    /**
     * Returns stock to Redis then persists the release to DB.
     *
     * REQUIRES_NEW: same rationale as deductStock.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW,
            noRollbackFor = BizException.class)
    public void releaseStock(Long ticketTypeId, int quantity) {
        try {
            Long result = redisInventoryManager.releaseStock(ticketTypeId, quantity);

            if (Long.valueOf(1L).equals(result)) {
                log.info("[RedisInventory] Released in Redis: ticketTypeId={}, qty={}",
                        ticketTypeId, quantity);
                persistReleaseToDB(ticketTypeId, quantity);
                return;
            }

            if (Long.valueOf(0L).equals(result)) {
                // Key absent after Redis restart — release directly in DB.
                // Reconciliation job will rebuild the Redis key on next read.
                log.warn("[RedisInventory] Key absent in Redis during release, "
                        + "writing directly to DB: ticketTypeId={}", ticketTypeId);
                persistReleaseToDB(ticketTypeId, quantity);
            }

        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[RedisInventory] Redis unavailable during releaseStock, "
                            + "falling back to DB adapter: ticketTypeId={}, reason={}",
                    ticketTypeId, e.getMessage());
            dbFallbackAdapter.releaseStock(ticketTypeId, quantity);
        }
    }

    /**
     * Pre-flight stock check — a hint for early rejection, not an atomic guarantee.
     * The Lua script in deductStock is the correctness boundary, not this method.
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
     * Persists a Redis deduction to DB using a conditional UPDATE.
     *
     * A load-then-save (SELECT + UPDATE) has a race window: two threads could
     * both SELECT the same value and produce an incorrect final result. The
     * conditional UPDATE is atomic at the DB level — no race window exists.
     *
     * When affected rows == 0, Redis and DB have drifted. Redis was already
     * deducted, so compensate before rejecting. The caller's transaction rolls
     * back, leaving DB clean. Reconciliation confirms consistency.
     */
    private void persistDeductToDB(Long ticketTypeId, int quantity) {
        int updated = inventoryRepository.guardDeductStock(ticketTypeId, quantity);

        if (updated == 0) {
            compensateRedis(ticketTypeId, quantity);
            throw BizException.of(ResultCode.INVENTORY_INSUFFICIENT,
                    "Tickets sold out (DB guard check failed)");
        }

        log.info("[RedisInventory] DB guard write succeeded: ticketTypeId={}, qty={}",
                ticketTypeId, quantity);
    }

    /**
     * Persists a stock release to DB.
     */
    private void persistReleaseToDB(Long ticketTypeId, int quantity) {
        int updated = inventoryRepository.guardReleaseStock(ticketTypeId, quantity);

        if (updated == 0) {
            log.error("[RedisInventory] DB release failed — inventory record missing: "
                    + "ticketTypeId={}. Manual reconciliation required.", ticketTypeId);
        } else {
            log.info("[RedisInventory] DB release write succeeded: ticketTypeId={}, qty={}",
                    ticketTypeId, quantity);
        }
    }

    /**
     * Adds quantity back to Redis after a DB guard write failure (best-effort).
     *
     * If Redis is also unavailable, the stock is temporarily under-counted.
     * InventoryReconciler will detect and correct this drift on its next run.
     */
    private void compensateRedis(Long ticketTypeId, int quantity) {
        try {
            redisInventoryManager.releaseStock(ticketTypeId, quantity);
            log.info("[RedisInventory] Redis compensation succeeded: "
                    + "ticketTypeId={}, qty={}", ticketTypeId, quantity);
        } catch (Exception compensationEx) {
            log.error("[RedisInventory] COMPENSATION FAILED — manual reconciliation required: "
                            + "ticketTypeId={}, qty={}, reason={}",
                    ticketTypeId, quantity, compensationEx.getMessage());
        }
    }

    /**
     * Loads available_stock from DB and writes it to Redis on cache miss.
     *
     * Concurrent cache misses are safe — both threads write the same committed
     * DB value; last-writer-wins is harmless at this point because the Lua
     * deduction has not yet run for either thread.
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
     */
    private void handleDeductResult(Long result, Long ticketTypeId) {
        if (Long.valueOf(1L).equals(result)) {
            log.info("[RedisInventory] Lua deduction succeeded: ticketTypeId={}", ticketTypeId);
            return;
        }

        if (Long.valueOf(0L).equals(result)) {
            throw BizException.of(ResultCode.INVENTORY_INSUFFICIENT, "Tickets sold out");
        }

        if (Long.valueOf(-1L).equals(result)) {
            // Key evicted immediately after lazy-load SET — extremely rare.
            // If this appears in production logs, check Redis maxmemory-policy.
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
