package com.chendev.ticketflow.integration;

import com.chendev.ticketflow.IntegrationTestBase;
import com.chendev.ticketflow.common.exception.DomainException;
import com.chendev.ticketflow.common.response.ResultCode;
import com.chendev.ticketflow.inventory.dto.StockView;
import com.chendev.ticketflow.inventory.redis.RedisInventoryManager;
import com.chendev.ticketflow.inventory.repository.InventoryRepository;
import com.chendev.ticketflow.inventory.service.InventoryQueryService;
import com.chendev.ticketflow.inventory.service.InventoryService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

// Integration test for the read path: real Redis + Postgres + Debezium CDC.  TICKET_TYPE_ID 999L is hermetic
// to this class, avoids collision with other tests sharing the suite-level Spring context and MeterRegistry.
// Redis-exception fallthrough is covered by InventoryQueryServiceFallthroughTest because (mocked)killing Testcontainers
// Redis mid-suite is fragile.
class InventoryQueryServiceTest extends IntegrationTestBase {

    private static final Long TICKET_TYPE_ID = 999L;
    private static final int INITIAL_STOCK = 100;

    private static final Duration CDC_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    @Autowired private InventoryQueryService inventoryQueryService;
    @Autowired private InventoryService inventoryService;
    @Autowired private InventoryRepository inventoryRepository;
    @Autowired private RedisInventoryManager redisInventoryManager;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        inventoryRepository.deleteAll();
        // flush Redis between tests: stale keys from prior runs would defeat the assertions
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    void cache_hit_returns_stock_from_redis() {
        inventoryService.initStock(TICKET_TYPE_ID, INITIAL_STOCK);
        // wait for CDC to propagate the INSERT before asserting on cache
        awaitRedisStock(INITIAL_STOCK);

        double hitsBefore = counterValue("ticketflow_inventory_query_cache_hits_total");

        StockView result = inventoryQueryService.getStock(TICKET_TYPE_ID);

        assertThat(result.getTicketTypeId()).isEqualTo(TICKET_TYPE_ID);
        assertThat(result.getAvailableStock()).isEqualTo(INITIAL_STOCK);
        assertThat(result.getSource()).isEqualTo(StockView.StockSource.CACHE);

        // >= because MeterRegistry is shared across the suite and other tests may
        // also drive cache hits before this one runs
        assertThat(counterValue("ticketflow_inventory_query_cache_hits_total"))
                .as("cache hit counter must increment for the hit we just executed")
                .isGreaterThanOrEqualTo(hitsBefore + 1);
    }

    @Test
    void cache_miss_falls_through_to_database() {
        // delete the key to simulate CDC lag or maxmemory eviction
        inventoryService.initStock(TICKET_TYPE_ID, INITIAL_STOCK);
        awaitRedisStock(INITIAL_STOCK);
        redisTemplate.delete("inventory:" + TICKET_TYPE_ID);

        double missesBefore = counterValue("ticketflow_inventory_query_cache_misses_total");

        StockView result = inventoryQueryService.getStock(TICKET_TYPE_ID);

        assertThat(result.getAvailableStock()).isEqualTo(INITIAL_STOCK);
        assertThat(result.getSource()).isEqualTo(StockView.StockSource.DATABASE);

        assertThat(counterValue("ticketflow_inventory_query_cache_misses_total"))
                .as("cache miss counter must increment when Redis returned null")
                .isGreaterThanOrEqualTo(missesBefore + 1);
    }

    @Test
    void unknown_ticket_type_throws_inventory_not_found() {
        // >= not == : MeterRegistry is suite-scoped, other tests may have already incremented this counter
        assertThatThrownBy(() -> inventoryQueryService.getStock(99999L))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getResultCode())
                .isEqualTo(ResultCode.INVENTORY_NOT_FOUND);
    }

    @Test
    void cache_reflects_db_writes_via_cdc() {
        inventoryService.initStock(TICKET_TYPE_ID, INITIAL_STOCK);
        awaitRedisStock(INITIAL_STOCK);

        StockView before = inventoryQueryService.getStock(TICKET_TYPE_ID);
        assertThat(before.getAvailableStock()).isEqualTo(INITIAL_STOCK);
        assertThat(before.getSource()).isEqualTo(StockView.StockSource.CACHE);

        // Drive a write through the production write path; CDC should mirror it to Redis
        inventoryService.dbDeduct(TICKET_TYPE_ID, 30);
        awaitRedisStock(INITIAL_STOCK - 30);

        StockView after = inventoryQueryService.getStock(TICKET_TYPE_ID);
        assertThat(after.getAvailableStock()).isEqualTo(INITIAL_STOCK - 30);
        assertThat(after.getSource())
                .as("after CDC propagation, the read should still be served from cache")
                .isEqualTo(StockView.StockSource.CACHE);
    }

    private double counterValue(String name) {
        Counter c = meterRegistry.find(name).counter();
        return c == null ? 0.0 : c.count();
    }

    private void awaitRedisStock(int expected) {
        await().atMost(CDC_TIMEOUT)
                .pollInterval(POLL_INTERVAL)
                .until(() -> {
                    Integer actual = redisInventoryManager.getStock(TICKET_TYPE_ID);
                    return actual != null && actual == expected;
                });
    }
}