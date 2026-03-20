package com.chendev.ticketflow.order.port;

// Port interface owned by Order domain.
// Order domain defines what it needs, not how it's implemented.
// Phase 3: swap LocalInventoryAdapter for RemoteInventoryAdapter — zero changes here.
public interface InventoryPort {

    // Deduct stock when order is created.
    void deductStock(Long ticketTypeId, int quantity);

    // Release stock synchronously (Redis + DB). Used by OrderService cancel paths.
    void releaseStock(Long ticketTypeId, int quantity);

    // Release stock in DB only. Used by OrderCancelledConsumer after Lua script
    // has already restored Redis inventory atomically. Calling releaseStock()
    // from the consumer would double-restore Redis.
    void releaseStockDbOnly(Long ticketTypeId, int quantity);

    // Pre-flight stock check — hint for early rejection, not an atomic guarantee.
    boolean hasSufficientStock(Long ticketTypeId, int quantity);
}