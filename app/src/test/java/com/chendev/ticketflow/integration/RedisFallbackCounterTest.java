package com.chendev.ticketflow.integration;

import com.chendev.ticketflow.IntegrationTestBase;
import com.chendev.ticketflow.infrastructure.adapter.InventoryAdapter;
import com.chendev.ticketflow.inventory.repository.InventoryRepository;
import com.chendev.ticketflow.inventory.service.InventoryService;
import com.chendev.ticketflow.order.port.InventoryPort.DeductionResult;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class RedisFallbackCounterTest extends IntegrationTestBase {

    private static final Long TICKET_TYPE_ID = 7777L;
    private static final int INITIAL_STOCK = 50;

    @Autowired private MeterRegistry meterRegistry;
    @Autowired private InventoryAdapter inventoryAdapter;
    @Autowired private InventoryService inventoryService;
    @Autowired private InventoryRepository inventoryRepository;
    @Autowired private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        inventoryRepository.deleteAll();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        inventoryService.initStock(TICKET_TYPE_ID, INITIAL_STOCK);
    }

    @Test
    void counter_is_registered_at_startup() {
        assertThat(meterRegistry.find("ticketflow_redis_fallback_total").counter())
                .as("redis fallback counter must be registered by InventoryMetrics @PostConstruct")
                .isNotNull();
    }

    @Test
    void counter_increments_on_cache_miss() {
        // Simulate cache miss: delete the Redis key, leave the DB row intact.
        // This is the CACHE_MISS path (luaResult == -1) in InventoryAdapter.
        redisTemplate.delete("inventory:" + TICKET_TYPE_ID);

        double before = meterRegistry.find("ticketflow_redis_fallback_total")
                .counter().count();

        DeductionResult result = inventoryAdapter.deductStock(TICKET_TYPE_ID, 1);

        double after = meterRegistry.find("ticketflow_redis_fallback_total")
                .counter().count();

        assertThat(result).isEqualTo(DeductionResult.SUCCESS);
        assertThat(after - before)
                .as("CACHE_MISS path must increment fallback counter exactly once")
                .isEqualTo(1.0);
    }
}