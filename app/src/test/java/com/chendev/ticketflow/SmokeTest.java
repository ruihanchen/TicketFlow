package com.chendev.ticketflow;

import com.chendev.ticketflow.inventory.repository.InventoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// @AutoConfigureMockMvc scoped to this class only, IntegrationTestBase is unchanged
@AutoConfigureMockMvc
class SmokeTest extends IntegrationTestBase {

    @Autowired
    private InventoryRepository inventoryRepository;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void contextLoads_and_flyway_migrations_applied() {
        assertThat(inventoryRepository.count()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void actuator_health_endpoint_returns_200() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void actuator_prometheus_endpoint_returns_200_with_metrics() throws Exception {
        String body = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Prometheus text exposition format: every metric family starts with '# HELP' and '# TYPE'
        // asserting on body content, not Content-Type header, because header format varies across Spring Boot versions
        assertThat(body).contains("# HELP");
        assertThat(body).contains("# TYPE");
    }
}