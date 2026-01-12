package com.chendev.ticketflow.inventory.service;

import com.chendev.ticketflow.common.exception.DomainException;
import com.chendev.ticketflow.common.response.ResultCode;
import com.chendev.ticketflow.infrastructure.metrics.InventoryMetrics;
import com.chendev.ticketflow.inventory.dto.StockView;
import com.chendev.ticketflow.inventory.entity.Inventory;
import com.chendev.ticketflow.inventory.redis.RedisInventoryManager;
import com.chendev.ticketflow.inventory.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// Read side of the inventory architecture: Redis (CDC-populated) first, DB fallback on miss or exception.
// A stale Redis value is a UX issue only -- the write path holds correctness via Postgres conditional UPDATE.
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryQueryService {

    private final RedisInventoryManager redisInventory;
    private final InventoryRepository inventoryRepository;
    private final InventoryMetrics inventoryMetrics;

    public StockView getStock(Long ticketTypeId) {
        try {
            Integer cached = redisInventory.getStock(ticketTypeId);
            if (cached != null) {
                inventoryMetrics.recordCacheHit();
                return new StockView(ticketTypeId, cached, StockView.StockSource.CACHE);
            }
            // cache miss is expected before CDC catches up or after key eviction(not an error)
            inventoryMetrics.recordCacheMiss();
        } catch (Exception e) {
            // Redis down/timeout/parse error: degrade to DB, don't propagate the cache error to the caller
            log.warn("[InventoryQuery] Redis read failed for ticketTypeId={}, falling through to DB: {}",
                    ticketTypeId, e.getMessage());
            inventoryMetrics.recordCacheFallthrough();
        }

        // INVENTORY_NOT_FOUND here means the ticket type doesn't exist, not a cache miss
        return inventoryRepository.findByTicketTypeId(ticketTypeId)
                .map(this::toView)
                .orElseThrow(() -> DomainException.of(ResultCode.INVENTORY_NOT_FOUND));
    }

    private StockView toView(Inventory inventory) {
        return new StockView(
                inventory.getTicketTypeId(),
                inventory.getAvailableStock(),
                StockView.StockSource.DATABASE);
    }
}