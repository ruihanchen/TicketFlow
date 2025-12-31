package com.chendev.ticketflow.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

//static facade for inventory correctness counters. JPA entities can't inject Spring beans, so counters are exposed
//via static accessors initialized at startup. Every increment here is a correctness invariant violation.
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
        // null guard: Hibernate may instantiate entities before Spring context is fully ready
        if (oversellCounter != null) {
            oversellCounter.increment();
        }
    }
}
