package com.chendev.ticketflow.inventory.redis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

// thin wrapper around Redis inventory operations.
// does NOT catch Redis exceptions; InventoryAdapter and ReconciliationService handle fallback.
@Slf4j
@Component
public class RedisInventoryManager {

    private static final String KEY_PREFIX = "inventory:";

    // Lua return code
    public static final int SUCCESS = 1;
    public static final int INSUFFICIENT = 0;
    public static final int CACHE_MISS = -1;

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> deductScript;

    public RedisInventoryManager(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.deductScript = new DefaultRedisScript<>();
        this.deductScript.setLocation(new ClassPathResource("scripts/inventory_deduct.lua"));
        this.deductScript.setResultType(Long.class);
    }

    // returns SUCCESS(1), INSUFFICIENT(0), or CACHE_MISS(-1)
    public int deduct(Long ticketTypeId, int quantity) {
        Long result = redisTemplate.execute(
                deductScript,
                List.of(keyOf(ticketTypeId)),
                String.valueOf(quantity));
        return result != null ? result.intValue() : CACHE_MISS;
    }

    // INCRBY is atomic: safe under concurrency
    public void release(Long ticketTypeId, int quantity) {
        redisTemplate.opsForValue().increment(keyOf(ticketTypeId), quantity);
    }

    // called from initStock() and ReconciliationService;full overwrite, not increment
    public void warmUp(Long ticketTypeId, int stock) {
        redisTemplate.opsForValue().set(keyOf(ticketTypeId), String.valueOf(stock));
    }

    // returns null if key doesn't exist; ReconciliationService treats null as redis < db
    public Integer getStock(Long ticketTypeId) {
        String value = redisTemplate.opsForValue().get(keyOf(ticketTypeId));
        return value != null ? Integer.parseInt(value) : null;
    }

    private String keyOf(Long ticketTypeId) {
        return KEY_PREFIX + ticketTypeId;
    }
}
