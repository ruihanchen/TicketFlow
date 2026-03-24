package com.chendev.ticketflow.infrastructure.adapter;

import com.chendev.ticketflow.common.exception.BizException;
import com.chendev.ticketflow.common.response.ResultCode;
import com.chendev.ticketflow.inventory.entity.Inventory;
import com.chendev.ticketflow.inventory.redis.RedisInventoryManager;
import com.chendev.ticketflow.inventory.repository.InventoryRepository;
import com.chendev.ticketflow.order.port.InventoryPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Redis-based inventory implementation.
 * * Performance Strategy:
 * We use a "Fast Path" approach where we try to deduct stock in Redis first using Lua scripts.
 * This happens outside of any DB transaction to avoid wasting database connections on
 * requests that will eventually fail due to lack of stock.
 *
 * Only when Redis confirms stock is available do we proceed to the database (persistDeduction).
 * If Redis is down, we fall back to a direct DB deduction to keep the system alive.
 */
@Slf4j
@Primary
@Component
public class RedisInventoryAdapter implements InventoryPort {

    private final RedisInventoryManager redisInventoryManager;
    private final InventoryRepository   inventoryRepository;
    private final InventoryAdapter      dbFallbackAdapter;

    /**
     * Local locks to prevent "thundering herd" issues during cache misses.
     * We're using a ConcurrentHashMap to keep locks granular (per ticketTypeId).
     * * Note: This map grows over time but since the number of active ticket types
     * is relatively small (<10k), it's not a memory concern. For high-SKU
     * environments, consider using Guava Striped locks instead.
     */
    private final ConcurrentHashMap<Long, Object> cacheMissLocks = new ConcurrentHashMap<>();

    public RedisInventoryAdapter(RedisInventoryManager redisInventoryManager,
                                 InventoryRepository inventoryRepository,
                                 InventoryAdapter dbFallbackAdapter) {
        this.redisInventoryManager = redisInventoryManager;
        this.inventoryRepository = inventoryRepository;
        this.dbFallbackAdapter = dbFallbackAdapter;
    }

    // ─── Core deduction flow (Phase 2 fast path) ─────────────────────────────

    /**
     * Deducts stock via Redis Lua script.
     * * No @Transactional here because we don't want to hold a DB connection
     * during the Redis IO. If Redis is down, we use the DB fallback which
     * handles its own transaction (REQUIRES_NEW).
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

    @Override
    public void persistDeduction(Long ticketTypeId, int quantity, DeductionResult result) {
        if (result == DeductionResult.DB_FALLBACK) {
            log.debug("[RedisInventory] Skipping persistDeduction — DB fallback " +
                    "already committed: ticketTypeId={}", ticketTypeId);
            return;
        }

        int updated = inventoryRepository.guardDeductStock(ticketTypeId, quantity);

        if (updated == 0) {
            throw BizException.of(ResultCode.INVENTORY_INSUFFICIENT,
                    "Tickets sold out (DB guard check failed)");
        }

        log.info("[RedisInventory] DB guard write succeeded: ticketTypeId={}, qty={}",
                ticketTypeId, quantity);
    }

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

    /**
     * Handles cache misses using a Double-Checked Locking (DCL) pattern.
     * * When the cache is cold, multiple concurrent threads will see a miss. To avoid
     * overwhelming the database with identical queries, we lock by ticketTypeId
     * so only one thread performs the DB read and populates Redis.
     */
    private void loadInventoryIntoRedis(Long ticketTypeId) {
        // First check (lock-free): skip if another thread already loaded.
        if (redisInventoryManager.getStock(ticketTypeId).isPresent()) {
            log.debug("[RedisInventory] Cache already populated, " +
                    "skipping DB read: ticketTypeId={}", ticketTypeId);
            return;
        }

        // Acquire per-ticketTypeId lock.
        Object lock = cacheMissLocks.computeIfAbsent(ticketTypeId, k -> new Object());

        synchronized (lock) {
            // Second check: the thread before us may have loaded it.
            if (redisInventoryManager.getStock(ticketTypeId).isPresent()) {
                log.debug("[RedisInventory] Lock acquired but cache already populated, " +
                        "skipping DB read: ticketTypeId={}", ticketTypeId);
                return;
            }

            // Only the first thread reaches here — single DB read.
            Inventory inventory = inventoryRepository.findByTicketTypeId(ticketTypeId)
                    .orElseThrow(() -> BizException.of(
                            ResultCode.TICKET_TYPE_NOT_FOUND,
                            "Inventory not found for ticketTypeId=" + ticketTypeId));

            boolean loaded = redisInventoryManager.setStockIfAbsent(
                    ticketTypeId, inventory.getAvailableStock());

            if (loaded) {
                log.info("[RedisInventory] Cache miss — loaded from DB: " +
                                "ticketTypeId={}, stock={}",
                        ticketTypeId, inventory.getAvailableStock());
            }
        }
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
