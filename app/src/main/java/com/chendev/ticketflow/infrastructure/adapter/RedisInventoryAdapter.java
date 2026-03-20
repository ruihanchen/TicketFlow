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
 * Uses atomic Lua scripts to prevent overselling during high-concurrency
 * "flash sale" scenarios. Implements a write-through strategy to the
 * database for persistence.
 *
 * Degradation path: If Redis is unavailable, the system automatically
 * falls back to the database-backed InventoryAdapter to maintain availability
 * at the cost of reduced throughput.
 */
@Slf4j
@Primary
@Component
@RequiredArgsConstructor
public class RedisInventoryAdapter implements InventoryPort {

    private final RedisInventoryManager redisInventoryManager;
    private final InventoryRepository   inventoryRepository;
    private final InventoryAdapter      dbFallbackAdapter;

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
            throw e;
        } catch (Exception e) {
            log.warn("[RedisInventory] Redis unavailable, falling back to DB adapter: "
                    + "ticketTypeId={}, reason={}", ticketTypeId, e.getMessage());
            dbFallbackAdapter.deductStock(ticketTypeId, quantity);
        }
    }

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
     *
     * On Redis failure: no fallback needed — Redis was already updated by Lua.
     * On DB failure: exception propagates to Kafka ErrorHandler for retry/DLQ.
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
            log.error("[RedisInventory] COMPENSATION FAILED — manual reconciliation required: "
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
