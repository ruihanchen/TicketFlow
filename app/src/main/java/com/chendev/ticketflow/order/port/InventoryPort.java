package com.chendev.ticketflow.order.port;

public interface InventoryPort {

    enum DeductionResult {
        SUCCESS,
        INSUFFICIENT,
        LOCK_CONFLICT,  // @Version conflict:caller returns 503, client should retry
    }

    DeductionResult deductStock(Long ticketTypeId, int quantity);

    void releaseStock(Long ticketTypeId, int quantity);
}