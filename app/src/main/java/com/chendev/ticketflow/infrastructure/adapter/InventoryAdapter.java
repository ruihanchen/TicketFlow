package com.chendev.ticketflow.infrastructure.adapter;

import com.chendev.ticketflow.inventory.service.InventoryService;
import com.chendev.ticketflow.order.port.InventoryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

//catch @Version conflicts here, outside the @Transactional boundary;if caught inside the service
//the TX becomes rollback-only and unusable
// TODO: this adapter is replaced with Redis Lua primary + DB conditional UPDATE fallback.
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryAdapter implements InventoryPort {

    private final InventoryService inventoryService;

    @Override
    public DeductionResult deductStock(Long ticketTypeId, int quantity) {
        try {
            return inventoryService.deductStock(ticketTypeId, quantity);
        } catch (ObjectOptimisticLockingFailureException e) {
            //version mismatch: another thread updated the inventory row first
            log.debug("[Inventory] version conflict: ticketTypeId={}", ticketTypeId);
            return DeductionResult.LOCK_CONFLICT;
        }
    }

    @Override
    public void releaseStock(Long ticketTypeId, int quantity) {
        inventoryService.releaseStock(ticketTypeId, quantity);
    }
}
