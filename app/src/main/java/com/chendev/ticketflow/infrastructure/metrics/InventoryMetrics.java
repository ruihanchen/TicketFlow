package com.chendev.ticketflow.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

//central registry for custom inventory counters. Static accessors let JPA entities
//(Hibernate-managed, not Spring-managed) record metrics without injection.
@Component
@RequiredArgsConstructor
public class InventoryMetrics {

    private final MeterRegistry registry;

    private static Counter oversellCounter;
    private static Counter redisFallbackCounter;

    @PostConstruct
    void init() {
        oversellCounter = Counter.builder("ticketflow_inventory_oversell_total")
                .description("deduct() called with insufficient stock; must always be 0 in healthy operation")
                .register(registry);

        redisFallbackCounter = Counter.builder("ticketflow_redis_fallback_total")
                .description("InventoryAdapter fell back from Redis to DB; " +
                        "sustained rate > 1/s indicates Redis degradation")
                .register(registry);
    }

    public static void recordOversellAttempt() {
        // null guard: Hibernate may instantiate Inventory before @PostConstruct runs
        if (oversellCounter != null) {
            oversellCounter.increment();
        }
    }

    public static void recordRedisFallback() {
        if (redisFallbackCounter != null) {
            redisFallbackCounter.increment();
        }
    }
}
