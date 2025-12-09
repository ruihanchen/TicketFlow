package com.chendev.ticketflow.inventory.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import com.chendev.ticketflow.inventory.repository.InventoryRepository;
import org.springframework.transaction.annotation.Transactional;
import com.chendev.ticketflow.inventory.entity.Inventory;

@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final InventoryRepository inventoryRepository;

    @Transactional
    public void initStock(Long ticketTypeId, int totalStock) {
        Inventory inventory = Inventory.init(ticketTypeId, totalStock);
        inventoryRepository.save(inventory);
        log.info("[Inventory] initialized: ticketTypeId={}, stock={}", ticketTypeId, totalStock);
    }
}
