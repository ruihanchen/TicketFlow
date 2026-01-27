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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

// Integration test for the read path with real Redis + Postgres + Debezium CDC.
// TICKET_TYPE_ID=999L is hermetic to this class. setUp/tearDown delete only this key
// rather than FLUSHALL to avoid wiping keys owned by other test classes.
// Redis-exception fallthrough is covered by InventoryQueryServiceFallthroughTest (Mockito).
class InventoryQueryServiceTest extends IntegrationTestBase {

    private static final Long     TICKET_TYPE_ID           = 999L;
    private static final int      INITIAL_STOCK            = 100;
    private static final int      DEDUCT_QTY               = 30;
    private static final int      RELEASE_TEST_QTY         = 40;
    private static final Long     NON_EXISTENT_TICKET_TYPE = 99999L;
    private static final String   REDIS_KEY                = "inventory:" + TICKET_TYPE_ID;

    private static final Duration CDC_TIMEOUT   = Duration.ofSeconds(10);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    @Autowired private InventoryQueryService inventoryQueryService;
    @Autowired private InventoryService      inventoryService;
    @Autowired private InventoryRepository   inventoryRepository;
    @Autowired private RedisInventoryManager redisInventoryManager;
    @Autowired private StringRedisTemplate   redisTemplate;
    @Autowired private MeterRegistry         meterRegistry;

    @BeforeEach
    void setUp() {
        inventoryRepository.findByTicketTypeId(TICKET_TYPE_ID).ifPresent(inventoryRepository::delete);
        redisTemplate.delete(REDIS_KEY);
    }

    @AfterEach
    void tearDown() {
        inventoryRepository.findByTicketTypeId(TICKET_TYPE_ID).ifPresent(inventoryRepository::delete);
        redisTemplate.delete(REDIS_KEY);
    }

    @Test
    void cache_hit_returns_stock_from_redis_with_cache_source() {
        inventoryService.initStock(TICKET_TYPE_ID, INITIAL_STOCK);
        // Must wait for CDC before asserting a cache hit; a premature read would be a miss.
        awaitRedisStock(INITIAL_STOCK);

        double hitsBefore = counterValue("ticketflow_inventory_query_cache_hits_total");
        StockView result  = inventoryQueryService.getStock(TICKET_TYPE_ID);

        assertThat(result.ticketTypeId()).isEqualTo(TICKET_TYPE_ID);
        assertThat(result.availableStock()).isEqualTo(INITIAL_STOCK);
        assertThat(result.source()).isEqualTo(StockView.StockSource.CACHE);
        // >= because MeterRegistry is suite-scoped; other tests may also drive cache hits
        assertThat(counterValue("ticketflow_inventory_query_cache_hits_total"))
                .isGreaterThanOrEqualTo(hitsBefore + 1);
    }

    @Test
    void cache_miss_falls_through_to_database_when_redis_key_absent() {
        inventoryService.initStock(TICKET_TYPE_ID, INITIAL_STOCK);
        awaitRedisStock(INITIAL_STOCK);
        redisTemplate.delete(REDIS_KEY); // simulate CDC lag or key eviction

        double missesBefore = counterValue("ticketflow_inventory_query_cache_misses_total");
        StockView result    = inventoryQueryService.getStock(TICKET_TYPE_ID);

        assertThat(result.availableStock()).isEqualTo(INITIAL_STOCK);
        assertThat(result.source()).isEqualTo(StockView.StockSource.DATABASE);
        assertThat(counterValue("ticketflow_inventory_query_cache_misses_total"))
                .isGreaterThanOrEqualTo(missesBefore + 1);
    }

    @Test
    void unknown_ticket_type_throws_inventory_not_found() {
        assertThatThrownBy(() -> inventoryQueryService.getStock(NON_EXISTENT_TICKET_TYPE))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getResultCode())
                .isEqualTo(ResultCode.INVENTORY_NOT_FOUND);
    }

    @Test
    void write_path_change_propagates_to_redis_via_cdc() {
        inventoryService.initStock(TICKET_TYPE_ID, INITIAL_STOCK);
        awaitRedisStock(INITIAL_STOCK);

        inventoryService.dbDeduct(TICKET_TYPE_ID, DEDUCT_QTY);
        awaitRedisStock(INITIAL_STOCK - DEDUCT_QTY);

        StockView after = inventoryQueryService.getStock(TICKET_TYPE_ID);
        assertThat(after.availableStock()).isEqualTo(INITIAL_STOCK - DEDUCT_QTY);
        assertThat(after.source()).isEqualTo(StockView.StockSource.CACHE);
    }

    @Test
    void cdc_propagation_reflects_stock_conservation_invariant() {
        inventoryService.initStock(TICKET_TYPE_ID, INITIAL_STOCK);
        awaitRedisStock(INITIAL_STOCK);

        inventoryService.dbDeduct(TICKET_TYPE_ID, RELEASE_TEST_QTY);
        awaitRedisStock(INITIAL_STOCK - RELEASE_TEST_QTY);

        inventoryService.releaseStock(TICKET_TYPE_ID, RELEASE_TEST_QTY);
        awaitRedisStock(INITIAL_STOCK);

        assertThat(inventoryQueryService.getStock(TICKET_TYPE_ID).availableStock())
                .isEqualTo(INITIAL_STOCK);
    }

    private double counterValue(String name) {
        Counter c = meterRegistry.find(name).counter();
        return c == null ? 0.0 : c.count();
    }

    private void awaitRedisStock(int expected) {
        await().atMost(CDC_TIMEOUT).pollInterval(POLL_INTERVAL)
                .until(() -> {
                    Integer actual = redisInventoryManager.getStock(TICKET_TYPE_ID);
                    return actual != null && actual == expected;
                });
    }
}