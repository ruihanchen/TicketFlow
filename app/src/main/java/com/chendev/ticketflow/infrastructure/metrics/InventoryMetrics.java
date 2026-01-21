package com.chendev.ticketflow.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// All counters are instance-based, injected into Spring services via constructor.
// Entities (Hibernate-managed) can't use DI, metrics calls belong in services.
@Component
@RequiredArgsConstructor
public class InventoryMetrics {

    private final MeterRegistry registry;

    // fires when guardDeduct returns affected=0, stock was insufficient for the request.
    // named "insufficient_stock" not "oversell" because guardDeduct makes oversell impossible.
    private Counter insufficientStock;

    private Counter cacheHits;
    private Counter cacheMisses;
    private Counter cacheFallthroughs;

    @PostConstruct
    void init() {
        insufficientStock = Counter.builder("ticketflow_inventory_insufficient_stock_total")
                .description("guardDeduct returned affected=0; stock was insufficient for the requested quantity")
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

    public void recordInsufficientStock() {
        insufficientStock.increment();
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