package com.chendev.ticketflow.inventory.service;

import com.chendev.ticketflow.common.exception.DomainException;
import com.chendev.ticketflow.common.response.ResultCode;
import com.chendev.ticketflow.inventory.entity.Inventory;
import com.chendev.ticketflow.inventory.redis.RedisInventoryManager;
import com.chendev.ticketflow.inventory.repository.InventoryRepository;
import com.chendev.ticketflow.order.port.InventoryPort.DeductionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final InventoryRepository inventoryRepository;
    private final RedisInventoryManager redisInventoryManager;

    @Transactional
    public void initStock(Long ticketTypeId, int totalStock) {
        inventoryRepository.save(Inventory.init(ticketTypeId, totalStock));

        // pre-populate Redis so the first read doesn't wait for CDC to catch up.
        // if Redis is down, Debezium sets the key on the next WAL event, correctness holds either way.
        try {
            redisInventoryManager.warmUp(ticketTypeId, totalStock);
        } catch (Exception e) {
            log.warn("[Inventory] Redis warm-up failed, CDC will populate on next update: ticketTypeId={}",
                    ticketTypeId);
        }

        log.info("[Inventory] initialized: ticketTypeId={}, stock={}", ticketTypeId, totalStock);
    }

    //@Version optimistic locking, no retry. Kept for ConcurrentInventoryTest to benchmark
    //lock contention vs guardDeduct. Not on the production path.
    @Transactional
    public DeductionResult deductStock(Long ticketTypeId, int quantity) {
        Inventory inv = inventoryRepository.findByTicketTypeId(ticketTypeId)
                .orElseThrow(() -> DomainException.of(ResultCode.INVENTORY_NOT_FOUND));

        if (inv.getAvailableStock() < quantity) {
            return DeductionResult.INSUFFICIENT;
        }

        inv.deduct(quantity);
        inventoryRepository.saveAndFlush(inv);
        //version mismatch → OptimisticLockingFailureException propagates to InventoryAdapter

        log.info("[Inventory] deducted: ticketTypeId={}, qty={}", ticketTypeId, quantity);
        return DeductionResult.SUCCESS;
    }

    //conditional UPDATE, zero retry. Production deduction path
    @Transactional
    public DeductionResult dbDeduct(Long ticketTypeId, int quantity) {
        int affected = inventoryRepository.guardDeduct(ticketTypeId, quantity);
        if (affected == 0) {
            return DeductionResult.INSUFFICIENT;
        }
        log.debug("[Inventory] DB deducted: ticketTypeId={}, qty={}", ticketTypeId, quantity);
        return DeductionResult.SUCCESS;
    }

    //guardRelease (conditional UPDATE) avoids @Version conflicts with concurrent guardDeduct on the same row
    @Transactional
    public void releaseStock(Long ticketTypeId, int quantity) {
        int affected = inventoryRepository.guardRelease(ticketTypeId, quantity);
        if (affected == 0) {
            // no inventory row found: throwing keeps the failure visible, not silently lost
            throw DomainException.of(ResultCode.INVENTORY_NOT_FOUND,
                    "cannot release stock: inventory not found for ticketTypeId=" + ticketTypeId);
        }
        log.info("[Inventory] released: ticketTypeId={}, qty={}", ticketTypeId, quantity);
    }
}
