package com.chendev.ticketflow.infrastructure.adapter;

import com.chendev.ticketflow.inventory.redis.RedisInventoryManager;
import com.chendev.ticketflow.inventory.service.InventoryService;
import com.chendev.ticketflow.order.port.InventoryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

//inventory adapter: Redis Lua primary → DB conditional UPDATE fallback.
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryAdapter implements InventoryPort {

    private final RedisInventoryManager redisInventoryManager;
    private final InventoryService inventoryService;

    @Override
    public DeductionResult deductStock(Long ticketTypeId, int quantity) {
        try {
            int luaResult = redisInventoryManager.deduct(ticketTypeId, quantity);

            if (luaResult == RedisInventoryManager.SUCCESS) {
                // Redis reserved:sync DB within caller's @Transactional
                DeductionResult dbResult = inventoryService.dbDeduct(ticketTypeId, quantity);
                if (dbResult != DeductionResult.SUCCESS) {
                    // Redis/DB drift: Redis had stock but DB didn't; compensate Redis
                    redisInventoryManager.release(ticketTypeId, quantity);
                    log.warn("[Inventory] Redis/DB drift on deduct: ticketTypeId={}", ticketTypeId);
                    return DeductionResult.INSUFFICIENT;
                }
                return DeductionResult.SUCCESS;
            }

            if (luaResult == RedisInventoryManager.INSUFFICIENT) {
                return DeductionResult.INSUFFICIENT;
            }

            // CACHE_MISS: treat the same as Redis DOWN(fall through to DB).
            log.debug("[Inventory] CACHE_MISS, falling back to DB: ticketTypeId={}", ticketTypeId);

        } catch (Exception e) {
            log.warn("[Inventory] Redis unavailable, falling back to DB: {}", e.getMessage());
        }

        // DB fallback: conditional UPDATE, zero retry
        return inventoryService.dbDeduct(ticketTypeId, quantity);
    }


    @Override
    public void releaseStock(Long ticketTypeId, int quantity) {
        //redis first: in the compensation path (TX rollback-only), INCRBY must complete
        //before DB throws. DB stock is already restored by TX rollback.
        try {
            redisInventoryManager.release(ticketTypeId, quantity);
        } catch (Exception e) {
            log.error("[Inventory] Redis release failed, reconciliation will correct: " +
                    "ticketTypeId={}, qty={}", ticketTypeId, quantity);
        }
        // not wrapped: cancellation errors must propagate;compensation path lets safeReleaseStock() catch it.
        inventoryService.releaseStock(ticketTypeId, quantity);
    }
}
