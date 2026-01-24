package com.chendev.ticketflow.event.port;

// Event domain's outbound port for inventory initialization.
// Separate from order.port.InventoryPort, different operation set, avoids name collision.
public interface InventoryInitPort {

    void initStock(Long ticketTypeId, int totalStock);
}