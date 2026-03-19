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

    private static final String INVENTORY_KEY_PREFIX        = "inventory:ticketType:";
    private static final String KAFKA_CONSUMED_PREFIX       = "kafka:consumed:";
    private static final long   KAFKA_IDEMPOTENCY_TTL_SECS  = 86400L; // 24h

    private final StringRedisTemplate        redisTemplate;
    private final RedisScript<Long>          deductStockScript;
    private final RedisScript<Long>          releaseStockScript;
    private final RedisScript<String>        releaseStockIdempotentScript;

    /**
     * Executes the atomic deduct_stock Lua script.
     * Returns: 1 (success), 0 (insufficient stock), -1 (cache miss).
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
     * Returns: 1 (success), 0 (key absent — Redis restarted).
     */
    public Long releaseStock(Long ticketTypeId, int quantity) {
        return redisTemplate.execute(
                releaseStockScript,
                List.of(inventoryKey(ticketTypeId)),
                String.valueOf(quantity)
        );
    }

    /**
     * Idempotent release for Kafka consumer — combines idempotency check,
     * INCRBY, and SETEX in one atomic Lua command.
     *
     * Returns "OK" on first processing, "DUPLICATE" if already processed,
     * "CACHE_MISS" if the inventory key is absent (Redis restart/eviction).
     * Caller handles each case; see OrderCancelledConsumer.
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

    public void setStock(Long ticketTypeId, int stock) {
        redisTemplate.opsForValue().set(inventoryKey(ticketTypeId), String.valueOf(stock));
    }

    public static String inventoryKey(Long ticketTypeId) {
        return INVENTORY_KEY_PREFIX + ticketTypeId;
    }

    public static String kafkaConsumedKey(String messageId) {
        return KAFKA_CONSUMED_PREFIX + messageId;
    }
}
