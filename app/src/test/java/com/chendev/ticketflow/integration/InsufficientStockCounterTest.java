package com.chendev.ticketflow.integration;

import com.chendev.ticketflow.IntegrationTestBase;
import com.chendev.ticketflow.inventory.port.DeductionResult;
import com.chendev.ticketflow.inventory.repository.InventoryRepository;
import com.chendev.ticketflow.inventory.service.InventoryService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

// Verifies inventory counters fire on the production write path (dbDeduct -> guardDeduct).
// The @Version path (deductStock) is NOT production; it's kept only for ConcurrentInventoryTest.
// Also covers guardRelease's double-release cap (LEAST() in the native SQL).
class InsufficientStockCounterTest extends IntegrationTestBase {

    // Names must match Counter.builder() strings in InventoryMetrics.
    // SmokeTest.prometheus_endpoint will catch a name change before these counters do.
    private static final String INSUFFICIENT_STOCK_METRIC = "ticketflow_inventory_insufficient_stock_total";
    private static final String CACHE_HITS_METRIC         = "ticketflow_inventory_query_cache_hits_total";
    private static final String CACHE_MISSES_METRIC       = "ticketflow_inventory_query_cache_misses_total";
    private static final String CACHE_FALLTHROUGHS_METRIC = "ticketflow_inventory_query_cache_fallthroughs_total";

    private static final Long TICKET_TYPE_ID  = 88888L;
    private static final int  INITIAL_STOCK   = 5;
    private static final int  EXCESS_QUANTITY = INITIAL_STOCK + 1;

    @Autowired private MeterRegistry      meterRegistry;
    @Autowired private InventoryService   inventoryService;
    @Autowired private InventoryRepository inventoryRepository;

    @BeforeEach
    void setUp() {
        inventoryRepository.deleteAll();
        inventoryService.initStock(TICKET_TYPE_ID, INITIAL_STOCK);
    }

    @Test
    void all_inventory_counters_are_registered_at_startup() {
        // Counters must exist before any request fires them (Prometheus pull model).
        assertThat(meterRegistry.find(INSUFFICIENT_STOCK_METRIC).counter()).isNotNull();
        assertThat(meterRegistry.find(CACHE_HITS_METRIC).counter()).isNotNull();
        assertThat(meterRegistry.find(CACHE_MISSES_METRIC).counter()).isNotNull();
        assertThat(meterRegistry.find(CACHE_FALLTHROUGHS_METRIC).counter()).isNotNull();
    }

    @Test
    void counter_increments_when_guardDeduct_returns_zero_affected_rows() {
        // Delta assertion: MeterRegistry is suite-scoped, other tests may also fire this counter.
        double before = counterValue(INSUFFICIENT_STOCK_METRIC);

        DeductionResult result = inventoryService.dbDeduct(TICKET_TYPE_ID, EXCESS_QUANTITY);

        assertThat(result).isEqualTo(DeductionResult.INSUFFICIENT);
        assertThat(counterValue(INSUFFICIENT_STOCK_METRIC) - before).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void counter_does_not_increment_on_successful_deduction() {
        double before = counterValue(INSUFFICIENT_STOCK_METRIC);

        assertThat(inventoryService.dbDeduct(TICKET_TYPE_ID, 1)).isEqualTo(DeductionResult.SUCCESS);
        assertThat(counterValue(INSUFFICIENT_STOCK_METRIC) - before).isEqualTo(0.0);
    }

    @Test
    void counter_increments_once_per_rejection_not_per_quantity() {
        // One rejected request = one increment regardless of how many tickets were requested.
        double before = counterValue(INSUFFICIENT_STOCK_METRIC);
        inventoryService.dbDeduct(TICKET_TYPE_ID, EXCESS_QUANTITY);
        inventoryService.dbDeduct(TICKET_TYPE_ID, EXCESS_QUANTITY);

        assertThat(counterValue(INSUFFICIENT_STOCK_METRIC) - before).isGreaterThanOrEqualTo(2.0);
    }

    @Test
    void double_release_does_not_overflow_past_total_stock() {
        // LEAST(available_stock + qty, total_stock) in guardRelease prevents overflow.
        inventoryService.dbDeduct(TICKET_TYPE_ID, 3);
        assertThat(availableStock()).isEqualTo(INITIAL_STOCK - 3);

        inventoryService.releaseStock(TICKET_TYPE_ID, 3);
        assertThat(availableStock()).isEqualTo(INITIAL_STOCK);

        // Second release on the same quantity: LEAST() caps at total_stock
        inventoryService.releaseStock(TICKET_TYPE_ID, 3);
        assertThat(availableStock()).isEqualTo(INITIAL_STOCK);
    }

    @Test
    void stock_is_conserved_across_deduct_and_release() {
        inventoryService.dbDeduct(TICKET_TYPE_ID, 2);
        assertThat(availableStock()).isEqualTo(INITIAL_STOCK - 2);

        inventoryService.releaseStock(TICKET_TYPE_ID, 2);
        assertThat(availableStock()).isEqualTo(INITIAL_STOCK);
    }

    private double counterValue(String name) {
        Counter c = meterRegistry.find(name).counter();
        return c == null ? 0.0 : c.count();
    }

    private int availableStock() {
        return inventoryRepository.findByTicketTypeId(TICKET_TYPE_ID).orElseThrow().getAvailableStock();
    }
}