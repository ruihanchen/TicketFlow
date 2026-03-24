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
 * Phase 2 architecture — Transactionless Fast Path:
 *
 *   deductStock()       — pure Redis Lua, NO @Transactional, zero DB connections.
 *   persistDeduction()  — DB guard write, joins caller's @Transactional (REQUIRED).
 *   compensateDeduction() — undo Redis (FAST_PATH) or DB (DB_FALLBACK).
 *
 * This separation means requests rejected by Redis never acquire a DB connection.
 * Only the ~1% that pass the Redis gate touch the database.
 *
 * Degradation: if Redis is unavailable, deductStock() falls back to the
 * DB adapter (REQUIRES_NEW). The system degrades to Phase 1 throughput
 * but remains correct — zero overselling guaranteed.
 */
@Slf4j
@Primary
@Component
@RequiredArgsConstructor
public class RedisInventoryAdapter implements InventoryPort {

    private final RedisInventoryManager redisInventoryManager;
    private final InventoryRepository   inventoryRepository;
    private final InventoryAdapter      dbFallbackAdapter;

    // ─── Core deduction flow (Phase 2 fast path) ─────────────────────────────

    /**
     * Deduct stock from Redis using atomic Lua script.
     *
     * NOT @Transactional — no DB connection is acquired on the hot path.
     *
     * If Redis is unavailable, delegates to the DB adapter which uses
     * REQUIRES_NEW. This acquires a short-lived connection for the fallback
     * deduction only — acceptable for the degraded path.
     *
     * Cache miss (Lua returns -1): loads inventory from DB into Redis via a
     * brief non-transactional read, then retries. This read acquires and
     * immediately releases a connection — it does not hold it for the
     * duration of the Lua call.
     */
    @Override
    public DeductionResult deductStock(Long ticketTypeId, int quantity) {
        try {
            Long result = redisInventoryManager.deductStock(ticketTypeId, quantity);

            if (Long.valueOf(-1L).equals(result)) {
                loadInventoryIntoRedis(ticketTypeId);
                result = redisInventoryManager.deductStock(ticketTypeId, quantity);
            }

            handleDeductResult(result, ticketTypeId);
            return DeductionResult.FAST_PATH;

        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[RedisInventory] Redis unavailable, falling back to DB adapter: "
                    + "ticketTypeId={}, reason={}", ticketTypeId, e.getMessage());
            dbFallbackAdapter.deductStock(ticketTypeId, quantity);
            return DeductionResult.DB_FALLBACK;
        }
    }

    /**
     * Sync the Redis deduction to DB via guard write.
     * Joins the caller's @Transactional (REQUIRED propagation — no annotation
     * needed since REQUIRED is the default when called within a tx context).
     *
     * No-op when the DB fallback was used — it already committed in its
     * own REQUIRES_NEW transaction.
     *
     * If the guard check fails (Redis/DB drift), throws BizException.
     * The caller is responsible for calling compensateDeduction().
     */
    @Override
    public void persistDeduction(Long ticketTypeId, int quantity, DeductionResult result) {
        if (result == DeductionResult.DB_FALLBACK) {
            log.debug("[RedisInventory] Skipping persistDeduction — DB fallback " +
                    "already committed: ticketTypeId={}", ticketTypeId);
            return;
        }

        int updated = inventoryRepository.guardDeductStock(ticketTypeId, quantity);

        if (updated == 0) {
            // Redis deducted but DB says insufficient — drift detected.
            // Don't compensate here — let the caller's compensateDeduction() handle it.
            throw BizException.of(ResultCode.INVENTORY_INSUFFICIENT,
                    "Tickets sold out (DB guard check failed)");
        }

        log.info("[RedisInventory] DB guard write succeeded: ticketTypeId={}, qty={}",
                ticketTypeId, quantity);
    }

    /**
     * Undo a deductStock() that was not successfully persisted.
     *
     * FAST_PATH:   Redis INCRBY only — DB was never modified, or was rolled
     *              back as part of the caller's failed transaction.
     * DB_FALLBACK: delegates to DB adapter's releaseStock — the fallback
     *              committed in its own REQUIRES_NEW and must be explicitly undone.
     */
    @Override
    public void compensateDeduction(Long ticketTypeId, int quantity, DeductionResult result) {
        if (result == DeductionResult.DB_FALLBACK) {
            log.info("[RedisInventory] Compensating DB fallback deduction: " +
                    "ticketTypeId={}, qty={}", ticketTypeId, quantity);
            dbFallbackAdapter.releaseStock(ticketTypeId, quantity);
        } else {
            compensateRedis(ticketTypeId, quantity);
        }
    }

    // ─── Release operations (unchanged from Phase 2 baseline) ────────────────

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
     * DB-only release for the Kafka consumer async path.
     *
     * The Lua script (release_stock_idempotent.lua) already restored Redis
     * inventory atomically in the same command as the idempotency check.
     * Calling releaseStock() here would INCRBY Redis a second time (+2 bug).
     * This method skips Redis entirely and only syncs DB.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseStockDbOnly(Long ticketTypeId, int quantity) {
        persistReleaseToDB(ticketTypeId, quantity);
        log.info("[RedisInventory] DB-only release (Kafka consumer path): " +
                "ticketTypeId={}, qty={}", ticketTypeId, quantity);
    }

    @Override
    public boolean hasSufficientStock(Long ticketTypeId, int quantity) {
        try {
            return redisInventoryManager.getStock(ticketTypeId)
                    .map(stock -> stock >= quantity)
                    .orElse(false);
        } catch (Exception e) {
            log.warn("[RedisInventory] Redis unavailable for stock check: " +
                    "ticketTypeId={}", ticketTypeId);
            return false;
        }
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

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

    private void compensateRedis(Long ticketTypeId, int quantity) {
        try {
            redisInventoryManager.releaseStock(ticketTypeId, quantity);
            log.info("[RedisInventory] Redis compensation succeeded: " +
                    "ticketTypeId={}, qty={}", ticketTypeId, quantity);
        } catch (Exception compensationEx) {
            log.error("[RedisInventory] COMPENSATION FAILED — reconciler will correct: "
                            + "ticketTypeId={}, qty={}, reason={}",
                    ticketTypeId, quantity, compensationEx.getMessage());
        }
    }

    private void loadInventoryIntoRedis(Long ticketTypeId) {
        Inventory inventory = inventoryRepository.findByTicketTypeId(ticketTypeId)
                .orElseThrow(() -> BizException.of(
                        ResultCode.TICKET_TYPE_NOT_FOUND,
                        "Inventory not found for ticketTypeId=" + ticketTypeId));

        redisInventoryManager.setStock(ticketTypeId, inventory.getAvailableStock());
        log.info("[RedisInventory] Cache miss — loaded from DB: ticketTypeId={}, stock={}",
                ticketTypeId, inventory.getAvailableStock());
    }

    private void handleDeductResult(Long result, Long ticketTypeId) {
        if (Long.valueOf(1L).equals(result)) return;
        if (Long.valueOf(0L).equals(result)) {
            throw BizException.of(ResultCode.INVENTORY_INSUFFICIENT, "Tickets sold out");
        }
        if (Long.valueOf(-1L).equals(result)) {
            throw BizException.of(ResultCode.INVENTORY_INSUFFICIENT,
                    "Inventory key evicted after lazy-load");
        }
    }
}
