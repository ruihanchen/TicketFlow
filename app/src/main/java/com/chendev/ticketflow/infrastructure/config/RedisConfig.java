package com.chendev.ticketflow.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Redis infrastructure configuration.
 *
 * Lua scripts are loaded once at startup and cached as RedisScript beans.
 * Loading at startup means:
 *   - ClassPathResource resolution errors surface at boot time, not at
 *     runtime when the first order is placed.
 *   - The script SHA is computed once and reused via EVALSHA for all
 *     subsequent calls, reducing Redis round-trip payload size.
 */
@Configuration
public class RedisConfig {

    /**
     * Atomic inventory deduction script.
     * Returns: 1 (success), 0 (insufficient stock), -1 (cache miss).
     * See: resources/scripts/deduct_stock.lua
     */
    @Bean
    public RedisScript<Long> deductStockScript() {
        return RedisScript.of(
                new ClassPathResource("scripts/deduct_stock.lua"),
                Long.class
        );
    }

    /**
     * Atomic inventory release script.
     * Returns: 1 (success), 0 (key absent — Redis restarted).
     * See: resources/scripts/release_stock.lua
     */
    @Bean
    public RedisScript<Long> releaseStockScript() {
        return RedisScript.of(
                new ClassPathResource("scripts/release_stock.lua"),
                Long.class
        );
    }
}
