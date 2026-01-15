package com.chendev.ticketflow.inventory.dto;

// Response for GET /api/v1/ticket-types/{id}/stock.`source` is advisory, useful for tests and client-side
// observability, but clients must not rely on it for correctness.
// Record for consistency with TicketTypeInfo; immutable value types in this codebase use records.
public record StockView(Long ticketTypeId, Integer availableStock, StockSource source) {

    public enum StockSource {
        CACHE, // Redis, CDC-populated
        DATABASE // Redis returned null or threw
    }
}