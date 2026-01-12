package com.chendev.ticketflow.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// Static counters (oversellCounter) exist for JPA entities, which Hibernate instantiates
// outside Spring, DI is unavailable there, so static accessors are the only option.
// Instance counters (cache hit/miss/fallthrough) are for Spring services; no static indirection needed.
@Component
@RequiredArgsConstructor
public class InventoryMetrics {

    private final MeterRegistry registry;

    private static Counter oversellCounter;

    private Counter cacheHits;
    private Counter cacheMisses;
    private Counter cacheFallthroughs;

    @PostConstruct
    void init() {
        oversellCounter = Counter.builder("ticketflow_inventory_oversell_total")
                .description("deduct() called with insufficient stock; must always be 0 in healthy operation")
                .register(registry);

        cacheHits = Counter.builder("ticketflow_inventory_query_cache_hits_total")
                .description("inventory read served from Redis")
                .register(registry);

        cacheMisses = Counter.builder("ticketflow_inventory_query_cache_misses_total")
                .description("Redis returned null; fell through to DB")
                .register(registry);

        cacheFallthroughs = Counter.builder("ticketflow_inventory_query_cache_fallthroughs_total")
                .description("Redis threw an exception; fell through to DB")
                .register(registry);
    }

    public static void recordOversellAttempt() {
        // null guard: Hibernate may instantiate Inventory before @PostConstruct runs
        if (oversellCounter != null) {
            oversellCounter.increment();
        }
    }

    public void recordCacheHit() {
        cacheHits.increment();
    }

    public void recordCacheMiss() {
        cacheMisses.increment();
    }

    public void recordCacheFallthrough() {
        cacheFallthroughs.increment();
    }
}