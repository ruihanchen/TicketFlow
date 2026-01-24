package com.chendev.ticketflow.inventory.port;

// Inventory deduction outcome. Lives in inventory.port (the producer's boundary) so
// InventoryService doesn't have to import from order.port -- dependency flows order → inventory.
public enum DeductionResult {

    SUCCESS,

    INSUFFICIENT,

    // @Version path only, production never returns this. See InventoryService.deductStock().
    LOCK_CONFLICT,
}