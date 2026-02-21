package com.chendev.ticketflow;

import com.chendev.ticketflow.inventory.repository.InventoryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

class SmokeTest extends IntegrationTestBase {

    @Autowired
    private InventoryRepository inventoryRepository;

    @Test
    void contextLoads_and_databaseIsAccessible() {
        long count = inventoryRepository.count();
        assertThat(count).isGreaterThanOrEqualTo(0);
    }
}
