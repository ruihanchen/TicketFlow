package com.chendev.ticketflow.inventory.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Low-level Redis operations for inventory management.
 *
 * Single responsibility: knows HOW to talk to Redis.
 * Does NOT know what to do with the results — that is RedisInventoryAdapter's job.
 *
 * StringRedisTemplate stores and retrieves stock as plain strings ("42").
 * This matches what the Lua scripts read via redis.call('GET', key).
 */
@Component
@RequiredArgsConstructor
public class RedisInventoryManager {

    // Key format is defined here, once, as the single source of truth.
    // RedisInventoryAdapter and the reconciliation job both call inventoryKey()
    // rather than constructing the key string themselves.
    private static final String KEY_PREFIX = "inventory:ticketType:";

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> deductStockScript;
    private final RedisScript<Long> releaseStockScript;

    /**
     * Executes the atomic deduct_stock Lua script.
     *
     * @return 1L  — success
     *         0L  — insufficient stock
     *        -1L  — cache miss (key absent)
     */
    public Long deductStock(Long ticketTypeId, int quantity) {
        return redisTemplate.execute(
                deductStockScript,
                List.of(inventoryKey(ticketTypeId)),
                String.valueOf(quantity)
        );
    }

    /**
     * Executes the atomic release_stock Lua script.
     *
     * @return 1L — success
     *         0L — key absent (Redis restarted, reconciliation job will sync)
     */
    public Long releaseStock(Long ticketTypeId, int quantity) {
        return redisTemplate.execute(
                releaseStockScript,
                List.of(inventoryKey(ticketTypeId)),
                String.valueOf(quantity)
        );
    }

    /**
     * Reads the current stock value from Redis.
     * Returns empty if the key is absent (cache miss).
     */
    public Optional<Integer> getStock(Long ticketTypeId) {
        String value = redisTemplate.opsForValue().get(inventoryKey(ticketTypeId));
        return Optional.ofNullable(value).map(Integer::parseInt);
    }

    /**
     * Writes a stock value directly to Redis.
     * Used by lazy-load (cache miss recovery) and the reconciliation job.
     */
    public void setStock(Long ticketTypeId, int stock) {
        redisTemplate.opsForValue().set(inventoryKey(ticketTypeId), String.valueOf(stock));
    }

    /**
     * Returns the Redis key for a given ticket type.
     * Exposed as a static method so callers (e.g. reconciliation job)
     * can construct keys without holding a reference to this bean.
     */
    public static String inventoryKey(Long ticketTypeId) {
        return KEY_PREFIX + ticketTypeId;
    }
}
