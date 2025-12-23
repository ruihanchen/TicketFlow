package com.chendev.ticketflow.inventory.redis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

// thin wrapper around Redis inventory operations.
// does NOT catch Redis exceptions; InventoryAdapter handles fallback.
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

    public int deduct(Long ticketTypeId, int quantity) {
        Long result = redisTemplate.execute(
                deductScript,
                List.of(keyOf(ticketTypeId)),
                String.valueOf(quantity));
        return result != null ? result.intValue() : CACHE_MISS;
    }

    public void release(Long ticketTypeId, int quantity) {
        //INCRBY is atomic(safe under concurrency)
        redisTemplate.opsForValue().increment(keyOf(ticketTypeId), quantity);
    }

    //called from InventoryService.initStock(); DB row and Redis key initialized together
    public void warmUp(Long ticketTypeId, int stock) {
        redisTemplate.opsForValue().set(keyOf(ticketTypeId), String.valueOf(stock));
    }

    private String keyOf(Long ticketTypeId) {
        return KEY_PREFIX + ticketTypeId;
    }
}
