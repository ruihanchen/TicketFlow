package com.chendev.ticketflow.integration;

import com.chendev.ticketflow.IntegrationTestBase;
import com.chendev.ticketflow.inventory.repository.InventoryRepository;
import com.chendev.ticketflow.inventory.service.InventoryService;
import com.chendev.ticketflow.inventory.port.DeductionResult;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

// Verifies the insufficient-stock counter fires on the production write path
// (InventoryService.dbDeduct -> guardDeduct affected=0), not the @Version entity path
// which production never reaches.
class InsufficientStockCounterTest extends IntegrationTestBase {

    private static final String METRIC_NAME = "ticketflow_inventory_insufficient_stock_total";
    private static final Long TICKET_TYPE_ID = 88888L;
    private static final int INITIAL_STOCK = 5;
    private static final int EXCESS_QUANTITY = INITIAL_STOCK + 1;

    @Autowired private MeterRegistry meterRegistry;
    @Autowired private InventoryService inventoryService;
    @Autowired private InventoryRepository inventoryRepository;

    @BeforeEach
    void setUp() {
        inventoryRepository.deleteAll();
        inventoryService.initStock(TICKET_TYPE_ID, INITIAL_STOCK);
    }

    @Test
    void counter_is_registered_at_startup() {
        // counter must exist before any rejection occurs, prometheus needs a stable metric name from startup
        assertThat(meterRegistry.find(METRIC_NAME).counter())
                .as("insufficient-stock counter must be registered by InventoryMetrics @PostConstruct")
                .isNotNull();
    }

    @Test
    void counter_increments_on_production_path_when_stock_insufficient() {
        // guardDeduct WHERE available_stock >= qty returns 0 rows when stock is 5 and qty is 10
        double before = meterRegistry.find(METRIC_NAME).counter().count();

        // Request more than available: guardDeduct WHERE available_stock >= 10
        // returns 0 affected rows because stock is 5.
        DeductionResult result = inventoryService.dbDeduct(TICKET_TYPE_ID, EXCESS_QUANTITY);

        assertThat(result)
                .as("deduction must be rejected when quantity exceeds available stock")
                .isEqualTo(DeductionResult.INSUFFICIENT);

        double after = meterRegistry.find(METRIC_NAME).counter().count();
        assertThat(after - before)
                .as("counter must increment exactly once per rejected deduction")
                .isEqualTo(1.0);
    }

    @Test
    void counter_does_not_increment_on_successful_deduction() {
        // successful deduction must not touch the counter, guards against accidental unconditional increment
        double before = meterRegistry.find(METRIC_NAME).counter().count();

        DeductionResult result = inventoryService.dbDeduct(TICKET_TYPE_ID, 1);

        assertThat(result).isEqualTo(DeductionResult.SUCCESS);

        double after = meterRegistry.find(METRIC_NAME).counter().count();
        assertThat(after - before)
                .as("successful deduction must not touch the insufficient-stock counter")
                .isEqualTo(0.0);
    }
}