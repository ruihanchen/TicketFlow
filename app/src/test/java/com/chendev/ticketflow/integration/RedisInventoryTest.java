package com.chendev.ticketflow.integration;

import com.chendev.ticketflow.IntegrationTestBase;
import com.chendev.ticketflow.inventory.entity.Inventory;
import com.chendev.ticketflow.inventory.redis.RedisInventoryManager;
import com.chendev.ticketflow.inventory.repository.InventoryRepository;
import com.chendev.ticketflow.inventory.service.InventoryService;
import com.chendev.ticketflow.order.port.InventoryPort;
import com.chendev.ticketflow.order.port.InventoryPort.DeductionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// Tests go through the full port/adapter chain to verify production behavior.
// setUp waits for CDC to propagate the INSERT so each test starts from a consistent
// DB+Redis baseline. DB assertions are synchronous; Redis assertions use Awaitility.
public class RedisInventoryTest extends IntegrationTestBase {

    private static final Long     TICKET_TYPE_ID     = 888L;
    private static final int      INITIAL_STOCK      = 100;
    private static final int      DEDUCT_QTY         = 3;    // any valid quantity < INITIAL_STOCK
    private static final int      CONCURRENT_THREADS = 200;
    private static final int      CONCURRENT_STOCK   = 50;   // < CONCURRENT_THREADS to force contention

    private static final Duration CDC_TIMEOUT   = Duration.ofSeconds(10);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    @Autowired private InventoryPort         inventoryPort;
    @Autowired private InventoryService      inventoryService;
    @Autowired private InventoryRepository   inventoryRepository;
    @Autowired private RedisInventoryManager redisInventoryManager;
    @Autowired private StringRedisTemplate   redisTemplate;

    @BeforeEach
    void setUp() {
        inventoryRepository.deleteAll();
        // flush Redis between tests: stale keys from previous runs cause false positives
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        inventoryService.initStock(TICKET_TYPE_ID, INITIAL_STOCK);
        // wait for CDC to propagate the INSERT; tests assert against a consistent DB+Redis state
        awaitRedisStock(INITIAL_STOCK);
    }

    @Test
    void redis_deduction_decrements_db_and_cdc_propagates_to_redis() {
        DeductionResult result = inventoryPort.deductStock(TICKET_TYPE_ID, DEDUCT_QTY);

        assertThat(result).isEqualTo(DeductionResult.SUCCESS);
        // DB is the source of truth; adapter returns only after guardDeduct commits
        assertThat(dbStock()).isEqualTo(INITIAL_STOCK - DEDUCT_QTY);
        // Redis catches up asynchronously via the Debezium pipeline
        awaitRedisStock(INITIAL_STOCK - DEDUCT_QTY);
    }

    @Test
    void redis_insufficient_does_not_change_stock() {
        DeductionResult result = inventoryPort.deductStock(TICKET_TYPE_ID, INITIAL_STOCK + 1);

        // no WAL event fires when guardDeduct returns 0 rows -- Redis stays unchanged, sync assertion is safe
        assertThat(result).isEqualTo(DeductionResult.INSUFFICIENT);
        assertThat(dbStock()).isEqualTo(INITIAL_STOCK);
        assertThat(redisInventoryManager.getStock(TICKET_TYPE_ID)).isEqualTo(INITIAL_STOCK);
    }

    @Test
    void concurrent_redis_deduction_zero_oversell() throws InterruptedException {
        // reinitialize with smaller stock to force contention; wait for CDC before launching threads
        inventoryRepository.deleteAll();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        inventoryService.initStock(TICKET_TYPE_ID, CONCURRENT_STOCK);
        awaitRedisStock(CONCURRENT_STOCK);

        AtomicInteger sold     = new AtomicInteger(0);
        AtomicInteger rejected = new AtomicInteger(0);

        CountDownLatch ready  = new CountDownLatch(CONCURRENT_THREADS);
        CountDownLatch start  = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(CONCURRENT_THREADS);

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_THREADS);

        for (int i = 0; i < CONCURRENT_THREADS; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    DeductionResult result = inventoryPort.deductStock(TICKET_TYPE_ID, 1);
                    if (result == DeductionResult.SUCCESS) {
                        sold.incrementAndGet();
                    } else {
                        rejected.incrementAndGet();
                    }
                } catch (Exception e) {
                    rejected.incrementAndGet();
                } finally {
                    finish.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        finish.await();
        pool.shutdown();

        int remaining = dbStock();

        // zero oversell: guardDeduct holds a row-level lock across check-and-decrement
        assertThat(sold.get() + remaining).isEqualTo(CONCURRENT_STOCK);
        assertThat(remaining).isGreaterThanOrEqualTo(0);

        // guardDeduct must neither oversell nor undersell: exactly CONCURRENT_STOCK threads succeed.
        // a weaker assertion (sold <= stock) would miss conditional-UPDATE bugs that undersell.
        assertThat(sold.get()).isEqualTo(CONCURRENT_STOCK);

        // Redis follows DB via CDC; WSL2 typically < 500ms
        awaitRedisStock(remaining);
    }

    private int dbStock() {
        return inventoryRepository.findByTicketTypeId(TICKET_TYPE_ID)
                .map(Inventory::getAvailableStock)
                .orElseThrow();
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