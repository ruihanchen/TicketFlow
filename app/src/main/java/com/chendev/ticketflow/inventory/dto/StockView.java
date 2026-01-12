package com.chendev.ticketflow.inventory.dto;

import lombok.Getter;

// Response for GET /api/v1/ticket-types/{id}/stock.  `source` is advisory, useful for integration
// tests and client-side observability, but clients must not rely on it for correctness.
@Getter
public class StockView {

    public enum StockSource {
        CACHE,      // served from Redis, pre-populated by CDC
        DATABASE    // Redis returned null or threw; fell through to DB
    }

    private final Long ticketTypeId;
    private final Integer availableStock;
    private final StockSource source;

    public StockView(Long ticketTypeId, Integer availableStock, StockSource source) {
        this.ticketTypeId = ticketTypeId;
        this.availableStock = availableStock;
        this.source = source;
    }
}