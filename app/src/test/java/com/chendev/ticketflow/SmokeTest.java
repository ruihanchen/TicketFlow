package com.chendev.ticketflow;

import com.chendev.ticketflow.inventory.repository.InventoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

//verifies Spring context loads and Flyway migrations ran cleanly.
class SmokeTest extends IntegrationTestBase {

    @Autowired
    private InventoryRepository inventoryRepository;

    @Test
    void contextLoads_and_flyway_migrations_applied() {
        assertThat(inventoryRepository.count()).isGreaterThanOrEqualTo(0);
    }
}
