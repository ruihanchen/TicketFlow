package com.chendev.ticketflow.infrastructure.adapter;

import com.chendev.ticketflow.common.exception.BizException;
import com.chendev.ticketflow.common.exception.SystemException;
import com.chendev.ticketflow.common.response.ResultCode;
import com.chendev.ticketflow.inventory.entity.Inventory;
import com.chendev.ticketflow.inventory.repository.InventoryRepository;
import com.chendev.ticketflow.order.port.InventoryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * DB-backed inventory adapter using JPA optimistic locking.
 *
 * Active in two scenarios:
 *   1. Phase 1 — sole InventoryPort implementation
 *   2. Phase 2 — fallback when Redis is unavailable
 *
 * In Phase 2, RedisInventoryAdapter (@Primary) delegates here on
 * RedisException. This adapter requires no changes for that role —
 * the Port & Adapter boundary absorbs the technology switch entirely.
 */
@Slf4j
//@Primary
@Component
@RequiredArgsConstructor
public class InventoryAdapter implements InventoryPort {

    private final InventoryRepository inventoryRepository;

    // noRollbackFor = BizException.class:
    // When OptimisticLockingFailureException is caught and re-thrown as BizException,
    // we do NOT want Spring to mark the shared transaction as rollback-only.
    // BizException is a handled business condition, not an unrecoverable error.
    // Without this, the outer createOrder() transaction would be poisoned before
    // its catch blocks have a chance to execute recovery logic.
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void deductStock(Long ticketTypeId, int quantity) {
        Inventory inventory = findInventory(ticketTypeId);
        try {
            inventory.deduct(quantity);
            inventoryRepository.save(inventory);
            inventoryRepository.flush(); // Force immediate SQL execution so that
            // OptimisticLockException surfaces here,
            // inside this try-catch, not at commit time.
            log.info("[Inventory] Deducted: ticketTypeId={}, quantity={}, remaining={}",
                    ticketTypeId, quantity, inventory.getAvailableStock());
        } catch (OptimisticLockingFailureException e) {
            // Another transaction updated inventory concurrently.
            // Surface this as a retryable business exception, not a system error.
            log.warn("[Inventory] Optimistic lock conflict: ticketTypeId={}", ticketTypeId);
            throw BizException.of(ResultCode.INVENTORY_LOCK_FAILED,
                    "High demand — please try again");
        }
    }

    @Override
    @Transactional
    public void releaseStock(Long ticketTypeId, int quantity) {
        Inventory inventory = findInventory(ticketTypeId);
        try {
            inventory.release(quantity);
            inventoryRepository.save(inventory);
            log.info("[Inventory] Released: ticketTypeId={}, quantity={}, remaining={}",
                    ticketTypeId, quantity, inventory.getAvailableStock());
        } catch (OptimisticLockingFailureException e) {
            log.warn("[Inventory] Optimistic lock conflict on release: ticketTypeId={}", ticketTypeId);
            throw BizException.of(ResultCode.INVENTORY_LOCK_FAILED,
                    "Failed to release stock — please retry");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasSufficientStock(Long ticketTypeId, int quantity) {
        return findInventory(ticketTypeId).getAvailableStock() >= quantity;
    }

    private Inventory findInventory(Long ticketTypeId) {
        return inventoryRepository.findByTicketTypeId(ticketTypeId)
                .orElseThrow(() -> new SystemException(ResultCode.INTERNAL_ERROR,
                        "Inventory record not found for ticketTypeId: " + ticketTypeId));
    }
}
