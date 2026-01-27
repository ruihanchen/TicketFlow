package com.chendev.ticketflow;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Sentinel tests: if any of these fail, nothing else in the suite is trustworthy.
// covers schema presence, service liveness, and metrics contract.
// @AutoConfigureMockMvc scoped here only, other test classes don't pay the init cost.
@AutoConfigureMockMvc
class SmokeTest extends IntegrationTestBase {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MockMvc      mockMvc;

    @Test
    void context_loads_and_all_flyway_migrations_applied() {
        Integer migrationCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true",
                Integer.class);
        assertThat(migrationCount).isGreaterThan(0);

        // if this number changes without updating the assertion, it surfaces in CI immediately
        assertThat(migrationCount)
                .as("expected 4 migrations V1-V4; update this count when adding a new one")
                .isEqualTo(4);
    }

    @Test
    void payment_expired_at_column_exists_in_orders_table() {
        // V4 adds this column. Hibernate maps a missing column to null, making isPaymentExpired() always return
        // false, a silent correctness bug.
        Integer columnExists = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns " +
                        "WHERE table_name = 'orders' AND column_name = 'payment_expired_at'",
                Integer.class);
        assertThat(columnExists)
                .as("payment_expired_at must exist in orders (V4 migration)")
                .isEqualTo(1);
    }

    @Test
    void replica_identity_full_is_set_on_inventories_table() {
        // V2 sets REPLICA IDENTITY FULL. Without it, CDC DELETE events carry only the PK, causing applyDelete()
        // to delete "inventory:0" instead of the correct key.
        String replicaIdentity = jdbcTemplate.queryForObject(
                "SELECT relreplident FROM pg_class WHERE relname = 'inventories'",
                String.class);
        assertThat(replicaIdentity)
                .as("inventories must have REPLICA IDENTITY FULL ('f') for CDC delete correctness")
                .isEqualTo("f");
    }

    @Test
    void actuator_health_endpoint_returns_200() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void prometheus_endpoint_exposes_all_business_metrics_with_correct_types() throws Exception {
        String body = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Check both name AND type, a gauge masquerading as a counter would break rate() in Grafana.
        assertThat(body).contains("# TYPE ticketflow_inventory_insufficient_stock_total counter");
        assertThat(body).contains("# TYPE ticketflow_order_reaper_cancelled_total counter");
        assertThat(body).contains("# TYPE ticketflow_cdc_events_total counter");
        assertThat(body).containsAnyOf(
                "# TYPE ticketflow_cdc_lag_seconds summary",
                "# TYPE ticketflow_cdc_lag_seconds histogram");
        assertThat(body).contains("# TYPE ticketflow_inventory_query_cache_hits_total counter");
        assertThat(body).contains("# TYPE ticketflow_inventory_query_cache_misses_total counter");
        assertThat(body).contains("# TYPE ticketflow_order_reaper_cycles_saturated_total counter");
    }
}