package com.chendev.ticketflow.integration;

import com.chendev.ticketflow.IntegrationTestBase;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

//adapter no longer touches Redis on the write path; CACHE_MISS fallback no longer exists,
//counter stays registered so dashboards that query the metric name don't break.
class RedisFallbackCounterTest extends IntegrationTestBase {

    @Autowired private MeterRegistry meterRegistry;

    @Test
    void counter_is_registered_at_startup() {
        assertThat(meterRegistry.find("ticketflow_redis_fallback_total").counter())
                .as("redis fallback counter must be registered by InventoryMetrics @PostConstruct")
                .isNotNull();
    }
}