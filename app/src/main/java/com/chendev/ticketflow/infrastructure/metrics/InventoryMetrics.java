package com.chendev.ticketflow.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// Inventory correctness counters. Static accessors allow JPA entities to record metrics without Spring injection,
// hibernate instantiates entities, not Spring
@Component
@RequiredArgsConstructor
public class InventoryMetrics {

    private final MeterRegistry registry;

    private static Counter oversellCounter;

    @PostConstruct
    void init() {
        oversellCounter = Counter.builder("ticketflow_inventory_oversell_total")
                .description("deduct() called with insufficient stock; must always be 0 in healthy operation")
                .register(registry);
    }

    public static void recordOversellAttempt() {
        // null guard: Hibernate may instantiate Inventory before @PostConstruct runs
        if (oversellCounter != null) {
            oversellCounter.increment();
        }
    }
}