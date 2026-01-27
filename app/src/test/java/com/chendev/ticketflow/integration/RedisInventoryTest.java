package com.chendev.ticketflow.integration;

import com.chendev.ticketflow.IntegrationTestBase;
import com.chendev.ticketflow.inventory.entity.Inventory;
import com.chendev.ticketflow.inventory.port.DeductionResult;
import com.chendev.ticketflow.inventory.redis.RedisInventoryManager;
import com.chendev.ticketflow.inventory.repository.InventoryRepository;
import com.chendev.ticketflow.inventory.service.InventoryService;
import com.chendev.ticketflow.order.port.InventoryPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// Production write path: InventoryPort -> InventoryService.dbDeduct() -> guardDeduct().
// DB is source of truth; Redis is updated asynchronously by CDC.
// setUp() waits for CDC to propagate initStock so tests start from a consistent baseline.
// ID isolation: TICKET_TYPE_ID=888L. AfterEach cleans only this row/key.
public class RedisInventoryTest extends IntegrationTestBase {

    private static final Long     TICKET_TYPE_ID     = 888L;
    private static final int      INITIAL_STOCK      = 100;
    private static final int      DEDUCT_QTY         = 3;
    // 200 threads vs 50 tickets: 4:1 contention ratio reliably exposes oversell bugs
    private static final int      CONCURRENT_THREADS = 200;
    private static final int      CONCURRENT_STOCK   = 50;
    private static final Duration CDC_TIMEOUT        = Duration.ofSeconds(10);
    private static final Duration POLL_INTERVAL      = Duration.ofMillis(100);
    private static final String   REDIS_KEY          = "inventory:" + TICKET_TYPE_ID;

    @Autowired private InventoryPort         inventoryPort;
    @Autowired private InventoryService      inventoryService;
    @Autowired private InventoryRepository   inventoryRepository;
    @Autowired private RedisInventoryManager redisInventoryManager;
    @Autowired private StringRedisTemplate   redisTemplate;

    @BeforeEach
    void setUp() {
        inventoryRepository.findByTicketTypeId(TICKET_TYPE_ID).ifPresent(inventoryRepository::delete);
        redisTemplate.delete(REDIS_KEY);
        inventoryService.initStock(TICKET_TYPE_ID, INITIAL_STOCK);
        awaitRedisStock(INITIAL_STOCK);
    }

    @AfterEach
    void tearDown() {
        inventoryRepository.findByTicketTypeId(TICKET_TYPE_ID).ifPresent(inventoryRepository::delete);
        redisTemplate.delete(REDIS_KEY);
    }

    @Test
    void successful_deduction_updates_db_immediately_and_redis_via_cdc() {
        DeductionResult result = inventoryPort.deductStock(TICKET_TYPE_ID, DEDUCT_QTY);

        assertThat(result).isEqualTo(DeductionResult.SUCCESS);
        assertThat(dbStock()).isEqualTo(INITIAL_STOCK - DEDUCT_QTY); // synchronous
        awaitRedisStock(INITIAL_STOCK - DEDUCT_QTY);                 // async via CDC
    }

    @Test
    void insufficient_deduction_returns_insufficient_and_does_not_change_stock() {
        DeductionResult result = inventoryPort.deductStock(TICKET_TYPE_ID, INITIAL_STOCK + 1);

        assertThat(result).isEqualTo(DeductionResult.INSUFFICIENT);
        assertThat(dbStock()).isEqualTo(INITIAL_STOCK);
        // guardDeduct returned 0 affected rows, so no WAL event was emitted and CDC never fires.
        // Safe to assert synchronously.
        assertThat(redisInventoryManager.getStock(TICKET_TYPE_ID)).isEqualTo(INITIAL_STOCK);
    }

    @Test
    void deduct_and_release_restores_stock_to_initial() {
        inventoryPort.deductStock(TICKET_TYPE_ID, DEDUCT_QTY);
        inventoryService.releaseStock(TICKET_TYPE_ID, DEDUCT_QTY);

        assertThat(dbStock()).isEqualTo(INITIAL_STOCK);
        awaitRedisStock(INITIAL_STOCK);
    }

    @Test
    void concurrent_deduction_zero_oversell_and_exactly_concurrent_stock_sold() throws InterruptedException {
        inventoryRepository.findByTicketTypeId(TICKET_TYPE_ID).ifPresent(inventoryRepository::delete);
        redisTemplate.delete(REDIS_KEY);
        inventoryService.initStock(TICKET_TYPE_ID, CONCURRENT_STOCK);
        awaitRedisStock(CONCURRENT_STOCK);

        AtomicInteger sold     = new AtomicInteger(0);
        AtomicInteger rejected = new AtomicInteger(0);
        AtomicReference<Throwable> unexpectedError = new AtomicReference<>();

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
                    (result == DeductionResult.SUCCESS ? sold : rejected).incrementAndGet();
                } catch (Exception e) {
                    unexpectedError.compareAndSet(null, e);
                } finally {
                    finish.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        finish.await();
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        assertThat(unexpectedError.get()).isNull();

        int remaining = dbStock();

        // Zero-oversell: sold + remaining must equal CONCURRENT_STOCK exactly
        assertThat(sold.get() + remaining).isEqualTo(CONCURRENT_STOCK);
        assertThat(remaining).isGreaterThanOrEqualTo(0);

        // Zero-undersell: a weaker assertion (sold <= stock) would miss bugs that fail to
        // grant the last available ticket when two threads race simultaneously
        assertThat(sold.get()).isEqualTo(CONCURRENT_STOCK);

        awaitRedisStock(remaining);
    }

    private int dbStock() {
        return inventoryRepository.findByTicketTypeId(TICKET_TYPE_ID)
                .map(Inventory::getAvailableStock).orElseThrow();
    }

    private void awaitRedisStock(int expected) {
        await().atMost(CDC_TIMEOUT).pollInterval(POLL_INTERVAL)
                .until(() -> {
                    Integer actual = redisInventoryManager.getStock(TICKET_TYPE_ID);
                    return actual != null && actual == expected;
                });
    }
}