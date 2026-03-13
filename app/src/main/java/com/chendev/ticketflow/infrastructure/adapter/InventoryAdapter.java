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
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryAdapter implements InventoryPort {

    private final InventoryRepository inventoryRepository;

    @Override
    @Transactional
    public void deductStock(Long ticketTypeId, int quantity) {
        Inventory inventory = findInventory(ticketTypeId);
        try {
            inventory.deduct(quantity);
            inventoryRepository.save(inventory);
            log.info("[Inventory] Deducted: ticketTypeId={}, quantity={}, remaining={}",
                    ticketTypeId, quantity, inventory.getAvailableStock());
        } catch (OptimisticLockingFailureException e) {
            // Another transaction updated inventory concurrently
            // In MVP this surfaces as a failure — Phase 2 adds retry logic
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
            log.info("[Inventory] Released: ticketTypeId={}, quantity={}, available={}",
                    ticketTypeId, quantity, inventory.getAvailableStock());
        } catch (OptimisticLockingFailureException e) {
            // Release failure is a system-level concern — should not fail silently
            throw new SystemException(ResultCode.INTERNAL_ERROR,
                    "Failed to release inventory for ticketTypeId=" + ticketTypeId, e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasSufficientStock(Long ticketTypeId, int quantity) {
        return inventoryRepository.findByTicketTypeId(ticketTypeId)
                .map(inv -> inv.hasSufficientStock(quantity))
                .orElse(false);
    }

    private Inventory findInventory(Long ticketTypeId) {
        return inventoryRepository.findByTicketTypeId(ticketTypeId)
                .orElseThrow(() -> new SystemException(ResultCode.INTERNAL_ERROR,
                        "Inventory record missing for ticketTypeId=" + ticketTypeId));
    }
}
