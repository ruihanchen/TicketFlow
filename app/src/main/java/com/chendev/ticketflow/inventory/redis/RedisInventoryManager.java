package com.chendev.ticketflow.inventory.redis;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

// Read-only. Redis is written exclusively by the CDC pipeline (InventoryChangeHandler).
// No write methods(prevents accidental dual-write regression)
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisInventoryManager {

    private static final String KEY_PREFIX = "inventory:";

    private final StringRedisTemplate redisTemplate;

    //returns null if the key has not yet been populated by CDC or was evicted
    public Integer getStock(Long ticketTypeId) {
        String value = redisTemplate.opsForValue().get(KEY_PREFIX + ticketTypeId);
        return value != null ? Integer.parseInt(value) : null;
    }
}