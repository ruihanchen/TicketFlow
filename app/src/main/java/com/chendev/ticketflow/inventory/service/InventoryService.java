package com.chendev.ticketflow.inventory.service;

import com.chendev.ticketflow.common.exception.DomainException;
import com.chendev.ticketflow.common.response.ResultCode;
import com.chendev.ticketflow.infrastructure.metrics.InventoryMetrics;
import com.chendev.ticketflow.inventory.entity.Inventory;
import com.chendev.ticketflow.inventory.port.DeductionResult;
import com.chendev.ticketflow.inventory.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final InventoryRepository inventoryRepository;
    private final InventoryMetrics inventoryMetrics;

    @Transactional
    public void initStock(Long ticketTypeId, int totalStock) {
        inventoryRepository.save(Inventory.init(ticketTypeId, totalStock));

        log.info("[Inventory] initialized: ticketTypeId={}, stock={}", ticketTypeId, totalStock);
    }

    // @Version optimistic locking, no retry. Kept for ConcurrentInventoryTest to benchmark
    // lock contention vs guardDeduct. Not on the production path.
    @Transactional
    public DeductionResult deductStock(Long ticketTypeId, int quantity) {
        Inventory inv = inventoryRepository.findByTicketTypeId(ticketTypeId)
                .orElseThrow(() -> DomainException.of(ResultCode.INVENTORY_NOT_FOUND));

        if (inv.getAvailableStock() < quantity) {
            return DeductionResult.INSUFFICIENT;
        }

        inv.deduct(quantity);
        inventoryRepository.saveAndFlush(inv);

        log.info("[Inventory] deducted: ticketTypeId={}, qty={}", ticketTypeId, quantity);
        return DeductionResult.SUCCESS;
    }

    //conditional UPDATE, zero retry; sole production write path via InventoryAdapter.
    @Transactional
    public DeductionResult dbDeduct(Long ticketTypeId, int quantity) {
        int affected = inventoryRepository.guardDeduct(ticketTypeId, quantity);
        if (affected == 0) {
            // flash sale signal: rate here = users turned away by stock depletion
            inventoryMetrics.recordInsufficientStock();
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