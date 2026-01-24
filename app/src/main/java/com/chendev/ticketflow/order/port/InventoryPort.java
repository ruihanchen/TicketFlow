package com.chendev.ticketflow.order.port;

import com.chendev.ticketflow.inventory.port.DeductionResult;

// Order domain's outbound port for inventory. DeductionResult lives in inventory.port
// (the producer) so the dependency flows order → inventory, not the reverse.
public interface InventoryPort {

    DeductionResult deductStock(Long ticketTypeId, int quantity);

    void releaseStock(Long ticketTypeId, int quantity);
}