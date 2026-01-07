package com.chendev.ticketflow.integration;

import com.chendev.ticketflow.IntegrationTestBase;
import com.chendev.ticketflow.inventory.entity.Inventory;
import com.chendev.ticketflow.inventory.redis.RedisInventoryManager;
import com.chendev.ticketflow.inventory.repository.InventoryRepository;
import com.chendev.ticketflow.inventory.service.InventoryReconciliationService;
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

//tests go through the full port/adapter chain to verify production behavior,
//DB assertions are synchronous; Redis assertions use Awaitility because Redis is now
//updated asynchronously by the CDC pipeline, not by the adapter directly.
public class RedisInventoryTest extends IntegrationTestBase {

    private static final Long TICKET_TYPE_ID = 888L;
    private static final int INITIAL_STOCK = 100;

    private static final Duration CDC_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    @Autowired private InventoryPort inventoryPort;
    @Autowired private InventoryService inventoryService;
    @Autowired private InventoryRepository inventoryRepository;
    @Autowired private RedisInventoryManager redisInventoryManager;
    @Autowired private InventoryReconciliationService reconciliationService;
    @Autowired private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        inventoryRepository.deleteAll();
        //flush Redis between tests: stale keys from previous runs cause false positives
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        inventoryService.initStock(TICKET_TYPE_ID, INITIAL_STOCK);
    }

    @Test
    void redis_deduction_decrements_db_and_cdc_propagates_to_redis() {
        DeductionResult result = inventoryPort.deductStock(TICKET_TYPE_ID, 3);
        //DB commits synchronously; Redis follows via CDC
        assertThat(result).isEqualTo(DeductionResult.SUCCESS);
        assertThat(dbStock()).isEqualTo(INITIAL_STOCK - 3);
        awaitRedisStock(INITIAL_STOCK - 3);
    }

    @Test
    void redis_insufficient_does_not_change_stock() {
        DeductionResult result = inventoryPort.deductStock(TICKET_TYPE_ID, INITIAL_STOCK + 1);

        //guardDeduct returns 0 rows affected; no DB change, no WAL event, no Redis change
        assertThat(result).isEqualTo(DeductionResult.INSUFFICIENT);
        assertThat(dbStock()).isEqualTo(INITIAL_STOCK);
        assertThat(redisInventoryManager.getStock(TICKET_TYPE_ID)).isEqualTo(INITIAL_STOCK);
    }

    @Test
    void concurrent_redis_deduction_zero_oversell() throws InterruptedException {
        int threads = 200;
        int stock = 50;

        //reinitialize with smaller stock to force contention
        inventoryRepository.deleteAll();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        inventoryService.initStock(TICKET_TYPE_ID, stock);

        AtomicInteger sold = new AtomicInteger(0);
        AtomicInteger rejected = new AtomicInteger(0);

        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(threads);

        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
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

        System.out.printf(
                "%n[RedisInventoryTest] threads=%d, sold=%d, rejected=%d, " +
                        "dbRemaining=%d, redisRemaining=%s%n",
                threads, sold.get(), rejected.get(),
                remaining, redisInventoryManager.getStock(TICKET_TYPE_ID));

        //zero oversell: guardDeduct holds a row-level lock across check-and-decrement
        assertThat(sold.get() + remaining).isEqualTo(stock);
        assertThat(remaining).isGreaterThanOrEqualTo(0);

        // guardDeduct guarantee: exactly `stock` threads succeed, no more, no less.
        // a weaker assertion (sold <= stock) would miss conditional-UPDATE bugs that undersell.
        assertThat(sold.get()).isEqualTo(stock);

        // Redis follows DB via CDC; WSL2 typically < 500ms, cloud Postgres faster
        awaitRedisStock(remaining);
    }

    @Test
    void reconciliation_fixes_redis_below_db() {
        // Simulate: Redis deducted but order INSERT failed (crash/rollback).
        // Redis says 90, DB says 100.
        redisInventoryManager.warmUp(TICKET_TYPE_ID, 90);

        reconciliationService.reconcile();

        assertThat(redisInventoryManager.getStock(TICKET_TYPE_ID)).isEqualTo(INITIAL_STOCK);
    }

    @Test
    void reconciliation_does_not_auto_fix_redis_above_db() {
        // Simulate: Redis says 110, DB says 100. Should NOT happen normally.
        redisInventoryManager.warmUp(TICKET_TYPE_ID, 110);

        reconciliationService.reconcile();

        // Redis must NOT be reduced, alert only, no auto-fix; reducing Redis could hide real inventory from buyers.
        assertThat(redisInventoryManager.getStock(TICKET_TYPE_ID)).isEqualTo(110);
    }

    @Test
    void reconciliation_restores_missing_redis_key() {
        // Simulate: Redis restarted, key lost
        redisTemplate.delete("inventory:" + TICKET_TYPE_ID);
        assertThat(redisInventoryManager.getStock(TICKET_TYPE_ID)).isNull();

        reconciliationService.reconcile();

        assertThat(redisInventoryManager.getStock(TICKET_TYPE_ID)).isEqualTo(INITIAL_STOCK);
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