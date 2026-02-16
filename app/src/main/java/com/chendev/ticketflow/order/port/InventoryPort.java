package com.chendev.ticketflow.order.port;

// Port interface owned by Order domain
// Order domain defines what it needs, not how it's implemented
// Phase 2: swap LocalInventoryAdapter for RemoteInventoryAdapter — zero changes here
public interface InventoryPort {

    // Deduct stock when order is created
    void deductStock(Long ticketTypeId, int quantity);

    // Release stock when order is cancelled or expired
    void releaseStock(Long ticketTypeId, int quantity);

    // Check if sufficient stock exists before attempting deduction
    boolean hasSufficientStock(Long ticketTypeId, int quantity);
}
