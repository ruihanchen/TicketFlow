package com.chendev.ticketflow.integration;

import com.chendev.ticketflow.IntegrationTestBase;
import com.chendev.ticketflow.inventory.entity.Inventory;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OversellCounterTest extends IntegrationTestBase {

    @Autowired
    private MeterRegistry meterRegistry;

    @Test
    void counter_is_registered_at_startup() {
        // counter must exist before any oversell occurs, Prometheus needs a stable metric name from startup
        assertThat(meterRegistry.find("ticketflow_inventory_oversell_total").counter())
                .as("oversell counter must be registered by InventoryMetrics @PostConstruct")
                .isNotNull();
    }

    @Test
    void counter_increments_when_inventory_deduct_underflows() {
        double before = meterRegistry.find("ticketflow_inventory_oversell_total")
                .counter().count();

        Inventory inv = Inventory.init(99999L, 5);
        assertThatThrownBy(() -> inv.deduct(10))
                .isInstanceOf(IllegalStateException.class);

        double after = meterRegistry.find("ticketflow_inventory_oversell_total")
                .counter().count();

        assertThat(after - before).isEqualTo(1.0);
    }
}