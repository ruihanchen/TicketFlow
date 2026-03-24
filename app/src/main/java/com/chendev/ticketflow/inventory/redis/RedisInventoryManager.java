package com.chendev.ticketflow.inventory.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Handles direct Redis interactions for ticket inventory.
 * * This component focuses strictly on executing Redis commands and scripts.
 * Business logic and result interpretation are handled by the RedisInventoryAdapter.
 */
@Component
@RequiredArgsConstructor
public class RedisInventoryManager {

    private static final String INVENTORY_KEY_PREFIX       = "inventory:ticketType:";
    private static final String KAFKA_CONSUMED_PREFIX      = "kafka:consumed:";
    private static final long   KAFKA_IDEMPOTENCY_TTL_SECS = 86400L; // 24h

    private final StringRedisTemplate     redisTemplate;
    private final RedisScript<Long>       deductStockScript;
    private final RedisScript<Long>       releaseStockScript;
    private final RedisScript<String>     releaseStockIdempotentScript;

    /**
     * Attempts to atomically deduct stock via Lua.
     * @return 1 for success, 0 for insufficient funds, -1 for a cache miss.
     */
    public Long deductStock(Long ticketTypeId, int quantity) {
        return redisTemplate.execute(
                deductStockScript,
                List.of(inventoryKey(ticketTypeId)),
                String.valueOf(quantity)
        );
    }

    /**
     * Atomically restores stock to the pool.
     * @return 1 if successful, 0 if the key was missing (e.g., after a Redis flush).
     */
    public Long releaseStock(Long ticketTypeId, int quantity) {
        return redisTemplate.execute(
                releaseStockScript,
                List.of(inventoryKey(ticketTypeId)),
                String.valueOf(quantity)
        );
    }

    /**
     * Handles idempotent stock restoration for Kafka consumers.
     * * Uses a single Lua script to check the message ID and update inventory
     * simultaneously. This ensures that even if a message is retried, the
     * Redis count remains accurate.
     * * @return "OK" on success, "DUPLICATE" if already processed, or "CACHE_MISS".
     */
    public String releaseStockIdempotent(String messageId, Long ticketTypeId, int quantity) {
        return redisTemplate.execute(
                releaseStockIdempotentScript,
                List.of(kafkaConsumedKey(messageId), inventoryKey(ticketTypeId)),
                String.valueOf(quantity),
                String.valueOf(KAFKA_IDEMPOTENCY_TTL_SECS)
        );
    }

    public Optional<Integer> getStock(Long ticketTypeId) {
        String value = redisTemplate.opsForValue().get(inventoryKey(ticketTypeId));
        return Optional.ofNullable(value).map(Integer::parseInt);
    }

    /**
     * Forcefully updates the stock count.
     * Used by the Reconciler to sync Redis with the database.
     */
    public void setStock(Long ticketTypeId, int stock) {
        redisTemplate.opsForValue().set(inventoryKey(ticketTypeId), String.valueOf(stock));
    }

    /**
     * Sets the stock only if it doesn't already exist.
     * * Used when loading the cache to prevent "thundering herd" issues where
     * multiple threads try to write the same value at once.
     * * @return true if this thread successfully initialized the key.
     */
    public boolean setStockIfAbsent(Long ticketTypeId, int stock) {
        Boolean result = redisTemplate.opsForValue()
                .setIfAbsent(inventoryKey(ticketTypeId), String.valueOf(stock));
        return Boolean.TRUE.equals(result);
    }

    // --- Key Helpers ---
    // Note: If we move to Redis Cluster, we'll need Hash Tags (e.g. {ticketType:42})
    // to keep related keys on the same slot and avoid CROSSSLOT errors during Lua execution.

    public static String inventoryKey(Long ticketTypeId) {
        return INVENTORY_KEY_PREFIX + ticketTypeId;
    }

    public static String kafkaConsumedKey(String messageId) {
        return KAFKA_CONSUMED_PREFIX + messageId;
    }
}