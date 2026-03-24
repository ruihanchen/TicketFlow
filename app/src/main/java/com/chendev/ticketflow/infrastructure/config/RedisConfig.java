package com.chendev.ticketflow.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.RedisScript;

@Configuration
public class RedisConfig {

    // Scripts are pre-loaded to catch path errors early and leverage EVALSHA caching.
    // Simple SETNX operations are kept in Java; Lua is reserved for GET-then-SET atomicity.

    // Returns: 1=Success, 0=Insufficient, -1=Miss
    @Bean
    public RedisScript<Long> deductStockScript() {
        return RedisScript.of(
                new ClassPathResource("scripts/deduct_stock.lua"),
                Long.class
        );
    }

    // Returns: 1=Success, 0=Key missing
    @Bean
    public RedisScript<Long> releaseStockScript() {
        return RedisScript.of(
                new ClassPathResource("scripts/release_stock.lua"),
                Long.class
        );
    }

    // For Kafka: Atomically checks idempotency before incrementing.
    // Returns: "OK" | "DUPLICATE" | "CACHE_MISS"
    @Bean
    public RedisScript<String> releaseStockIdempotentScript() {
        return RedisScript.of(
                new ClassPathResource("scripts/release_stock_idempotent.lua"),
                String.class
        );
    }
}
