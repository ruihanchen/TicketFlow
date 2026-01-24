package com.chendev.ticketflow.infrastructure.adapter;

import com.chendev.ticketflow.event.port.InventoryInitPort;
import com.chendev.ticketflow.inventory.service.InventoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// Bridges event → inventory for stock init. Only class allowed to import both domains.
@Component
@RequiredArgsConstructor
public class InventoryInitAdapter implements InventoryInitPort {

    private final InventoryService inventoryService;

    @Override
    public void initStock(Long ticketTypeId, int totalStock) {
        inventoryService.initStock(ticketTypeId, totalStock);
    }
}