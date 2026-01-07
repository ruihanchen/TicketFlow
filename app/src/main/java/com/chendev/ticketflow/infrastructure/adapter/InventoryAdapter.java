package com.chendev.ticketflow.infrastructure.adapter;

import com.chendev.ticketflow.inventory.service.InventoryService;
import com.chendev.ticketflow.order.port.InventoryPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

//Inventory write path: DB only. Redis is updated asynchronously by the Debezium CDC pipeline.
//guardDeduct's conditional UPDATE serializes concurrent decrements under Postgres row-level lock,
//making oversell mathematically impossible without Redis involvement.
@Slf4j
@Component
@RequiredArgsConstructor
public class InventoryAdapter implements InventoryPort {

    private final InventoryService inventoryService;

    @Override
    public DeductionResult deductStock(Long ticketTypeId, int quantity) {
        return inventoryService.dbDeduct(ticketTypeId, quantity);
    }

    @Override
    public void releaseStock(Long ticketTypeId, int quantity) {
        inventoryService.releaseStock(ticketTypeId, quantity);
    }
}