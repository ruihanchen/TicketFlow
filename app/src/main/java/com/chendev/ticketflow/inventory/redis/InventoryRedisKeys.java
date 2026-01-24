package com.chendev.ticketflow.inventory.redis;

// Centralizes the Redis key format so the read path and CDC write path can't drift apart silently.
public final class InventoryRedisKeys {

    public static final String STOCK_KEY_PREFIX = "inventory:";

    public static String stockKey(Long ticketTypeId) {
        return STOCK_KEY_PREFIX + ticketTypeId;
    }

    private InventoryRedisKeys() {}
}