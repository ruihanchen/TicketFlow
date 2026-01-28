package com.chendev.ticketflow.infrastructure.cdc;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

// Embedded Debezium runs in a daemon thread that Spring Boot's health system can't see.
// If it dies, /actuator/health still returns UP while CDC silently stops(Redis goes stale).
@Component
@RequiredArgsConstructor
public class DebeziumHealthIndicator implements HealthIndicator {

    private final DebeziumEngineLifecycle engineLifecycle;

    @Override
    public Health health() {
        // lightweight thread-state check only; no DB or Redis probe to avoid health-check latency spikes
        if (engineLifecycle.isEngineRunning()) {
            return Health.up()
                    .withDetail("engine", "running")
                    .build();
        }
        return Health.down()
                .withDetail("engine", "stopped or not started")
                .build();
    }
}