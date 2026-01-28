package com.chendev.ticketflow.order.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// created:HTTP 201 conflates new orders with idempotent replays; this counter counts only new ones.
// duplicate:replay requests intercepted at the requestId fast path; a spike here means client retry bug.
@Component
@RequiredArgsConstructor
public class OrderMetrics {

    private final MeterRegistry registry;

    private Counter created;
    private Counter duplicate;

    @PostConstruct
    void init() {
        // Named "_success" rather than "_created" because OpenMetrics reserves "_created"
        // for counter creation timestamps — Micrometer strips it, and the exporter emits
        // "ticketflow_orders_total" instead. Verified: /actuator/prometheus (2026-01-28).
        created = Counter.builder("ticketflow_orders_success_total")
                .description("new orders created (excludes idempotent replays)")
                .register(registry);

        duplicate = Counter.builder("ticketflow_orders_duplicate_total")
                .description("idempotent replay requests intercepted by requestId check")
                .register(registry);
    }

    public void recordCreated() {
        created.increment();
    }

    public void recordDuplicate() {
        duplicate.increment();
    }
}