package com.chendev.ticketflow.order.port;

import com.chendev.ticketflow.common.exception.BizException;

/**
 * Port interface owned by Order domain.
 * Order domain defines what it needs, not how it's implemented.
 * Phase 3: swap LocalInventoryAdapter for RemoteInventoryAdapter — zero changes here.
 *
 * Phase 2 flow (Transactionless Fast Path):
 *   1. deductStock()         — cache-layer deduction, NO DB connection
 *   2. persistDeduction()    — DB guard write, inside caller's @Transactional
 *   3. compensateDeduction() — undo deductStock() if persistOrder fails
 *
 * Phase 3 mapping:
 *   deductStock()         → HTTP reserve call to remote inventory service
 *   persistDeduction()    → HTTP confirm call (or no-op if reserve is final)
 *   compensateDeduction() → HTTP cancel-reservation call
 */
public interface InventoryPort {

    /**
     * Deduct stock atomically from the fast path (cache/memory layer).
     *
     * NOT transactional — must NOT acquire a DB connection on the hot path.
     * If the fast path is unavailable (e.g., Redis down), the adapter falls
     * back to a DB-only deduction in its own REQUIRES_NEW transaction.
     *
     * @return FAST_PATH if cache handled it; DB_FALLBACK if cache was unavailable
     * @throws BizException if stock is insufficient (code: INVENTORY_INSUFFICIENT)
     * @throws BizException if optimistic lock conflict in fallback (code: INVENTORY_LOCK_FAILED)
     */
    DeductionResult deductStock(Long ticketTypeId, int quantity);

    /**
     * Sync a fast-path deduction to the database.
     * Must be called within the caller's @Transactional context (REQUIRED propagation).
     *
     * No-op when result is DB_FALLBACK — the fallback already committed
     * the deduction in its own REQUIRES_NEW transaction.
     *
     * @throws BizException if the DB guard check fails (cache/DB drift detected)
     */
    void persistDeduction(Long ticketTypeId, int quantity, DeductionResult result);

    /**
     * Undo a deductStock() that was not successfully persisted.
     * Call when persistOrder() fails after deductStock() succeeded.
     *
     * FAST_PATH:   releases cache only (DB was never modified or was rolled back).
     * DB_FALLBACK: releases via DB adapter (fallback committed independently).
     */
    void compensateDeduction(Long ticketTypeId, int quantity, DeductionResult result);

    /**
     * Release stock (cache + DB). Used by async paths (Kafka cancel consumer)
     * and synchronous cancel operations.
     */
    void releaseStock(Long ticketTypeId, int quantity);

    /**
     * Release stock in DB only. Used by OrderCancelledConsumer after Lua script
     * has already restored Redis inventory atomically. Calling releaseStock()
     * from the consumer would double-restore Redis.
     */
    void releaseStockDbOnly(Long ticketTypeId, int quantity);

    /**
     * Pre-flight stock check — hint for early rejection, not an atomic guarantee.
     */
    boolean hasSufficientStock(Long ticketTypeId, int quantity);

    /**
     * Indicates which deduction path was used by deductStock().
     * Drives the behavior of persistDeduction() and compensateDeduction().
     */
    enum DeductionResult {
        /** Cache layer (Redis Lua) handled the deduction. Caller must persistDeduction(). */
        FAST_PATH,
        /** Cache unavailable; DB adapter handled the deduction via REQUIRES_NEW. */
        DB_FALLBACK
    }
}