package com.chendev.ticketflow.inventory.service;

import com.chendev.ticketflow.common.exception.DomainException;
import com.chendev.ticketflow.common.response.ResultCode;
import com.chendev.ticketflow.inventory.entity.Inventory;
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

    @Transactional
    public void initStock(Long ticketTypeId, int totalStock) {
        inventoryRepository.save(Inventory.init(ticketTypeId, totalStock));
        log.info("[Inventory] initialized: ticketTypeId={}, stock={}", ticketTypeId, totalStock);
    }

    @Transactional
    public DeductionResult deductStock(Long ticketTypeId, int quantity) {
        Inventory inv = inventoryRepository.findByTicketTypeId(ticketTypeId)
                .orElseThrow(() -> DomainException.of(ResultCode.INVENTORY_NOT_FOUND));

        if (inv.getAvailableStock() < quantity) {
            return DeductionResult.INSUFFICIENT;
        }

        inv.deduct(quantity);
        inventoryRepository.saveAndFlush(inv);
        // If version mismatch → OptimisticLockingFailureException propagates.
        // Adapter catches it and returns LOCK_CONFLICT.

        log.info("[Inventory] deducted: ticketTypeId={}, qty={}", ticketTypeId, quantity);
        return DeductionResult.SUCCESS;
    }

    @Transactional
    public void releaseStock(Long ticketTypeId, int quantity) {
        // throws if inventory not found;silent no-op would permanently lose the deducted stock
        Inventory inv = inventoryRepository.findByTicketTypeId(ticketTypeId)
                .orElseThrow(() -> DomainException.of(ResultCode.INVENTORY_NOT_FOUND));
        inv.release(quantity);
        inventoryRepository.save(inv);
        log.info("[Inventory] released: ticketTypeId={}, qty={}", ticketTypeId, quantity);
    }
}