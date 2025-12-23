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

        //warm up Redis alongside DB so both are in sync from the start; if Redis is down
        //during event creation, reconciliation restores the key within minutes.
        try {
            redisInventoryManager.warmUp(ticketTypeId, totalStock);
        } catch (Exception e) {
            log.warn("[Inventory] Redis warm-up failed, reconciliation will fix: ticketTypeId={}",
                    ticketTypeId);
        }

        log.info("[Inventory] initialized: ticketTypeId={}, stock={}", ticketTypeId, totalStock);
    }

    //@Version optimistic locking, no retry.Kept for ConcurrentInventoryTest; generates the lock contention
    //that benchmarks; the conditional UPDATE improvement. Not on the production path.
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

    //conditional UPDATE, zero retry; called by InventoryAdapter for both Redis-confirmed sync & Redis-down fallback.
    @Transactional
    public DeductionResult dbDeduct(Long ticketTypeId, int quantity) {
        int affected = inventoryRepository.guardDeduct(ticketTypeId, quantity);
        if (affected == 0) {
            return DeductionResult.INSUFFICIENT;
        }
        log.debug("[Inventory] DB deducted: ticketTypeId={}, qty={}", ticketTypeId, quantity);
        return DeductionResult.SUCCESS;
    }

    //Uses guardRelease (conditional UPDATE) instead of entity-based @Version. During a flash sale,
    //guardDeduct & releaseStock race on same row;entity-based release hits @Version conflicts ~10-50% of the time
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
