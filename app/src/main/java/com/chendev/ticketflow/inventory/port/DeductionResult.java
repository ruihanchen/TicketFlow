package com.chendev.ticketflow.inventory.port;

// Outcome of an inventory deduction attempt.
// Defined in inventory.port (the producer) so InventoryService doesn't import from order.port.
public enum DeductionResult {

    SUCCESS,

    INSUFFICIENT,
}