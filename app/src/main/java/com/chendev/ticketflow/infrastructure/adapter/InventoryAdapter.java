package com.chendev.ticketflow.infrastructure.adapter;

import com.chendev.ticketflow.common.exception.BizException;
import com.chendev.ticketflow.common.exception.SystemException;
import com.chendev.ticketflow.common.response.ResultCode;
import com.chendev.ticketflow.inventory.entity.Inventory;
import com.chendev.ticketflow.inventory.repository.InventoryRepository;
import com.chendev.ticketflow.order.port.InventoryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * In Phase 2, RedisInventoryAdapter (@Primary) delegates here when Redis
 * throws. The REQUIRES_NEW on deductStock() ensures the fallback deduction
 * commits independently — it does not nest inside the caller's transaction.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryAdapter implements InventoryPort {

    private final InventoryRepository inventoryRepository;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DeductionResult deductStock(Long ticketTypeId, int quantity) {
        Inventory inventory = findInventory(ticketTypeId);
        try {
            inventory.deduct(quantity);
            inventoryRepository.save(inventory);
            inventoryRepository.flush();
            log.info("[Inventory] Deducted: ticketTypeId={}, quantity={}, remaining={}",
                    ticketTypeId, quantity, inventory.getAvailableStock());
            return DeductionResult.DB_FALLBACK;
        } catch (OptimisticLockingFailureException e) {
            log.warn("[Inventory] Optimistic lock conflict: ticketTypeId={}", ticketTypeId);
            throw BizException.of(ResultCode.INVENTORY_LOCK_FAILED,
                    "High demand — please try again");
        }
    }

    /**
     * No-op — deductStock() already performed the full DB deduction.
     * The DB_FALLBACK result signals that no additional persistence is needed.
     */
    @Override
    public void persistDeduction(Long ticketTypeId, int quantity, DeductionResult result) {
        // Intentionally empty. deductStock committed via REQUIRES_NEW.
    }

    /**
     * Undo a deductStock(). Delegates to releaseStock() which handles
     * the optimistic-lock-safe increment.
     */
    @Override
    public void compensateDeduction(Long ticketTypeId, int quantity, DeductionResult result) {
        releaseStock(ticketTypeId, quantity);
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
            log.warn("[Inventory] Optimistic lock conflict on release: ticketTypeId={}",
                    ticketTypeId);
            throw BizException.of(ResultCode.INVENTORY_LOCK_FAILED,
                    "Failed to release stock — please retry");
        }
    }

    /**
     * DB-only release for the Kafka consumer async path.
     * Identical to releaseStock() in this adapter — Redis is not involved.
     */
    @Override
    @Transactional
    public void releaseStockDbOnly(Long ticketTypeId, int quantity) {
        releaseStock(ticketTypeId, quantity);
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
