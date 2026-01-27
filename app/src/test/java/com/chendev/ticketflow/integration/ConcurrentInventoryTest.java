package com.chendev.ticketflow.integration;

import com.chendev.ticketflow.IntegrationTestBase;
import com.chendev.ticketflow.inventory.entity.Inventory;
import com.chendev.ticketflow.inventory.port.DeductionResult;
import com.chendev.ticketflow.inventory.repository.InventoryRepository;
import com.chendev.ticketflow.inventory.service.InventoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

// Tests the @Version optimistic-locking path (InventoryService.deductStock()).
// This is NOT the production path; it's kept only for benchmarking lock contention vs guardDeduct.
// The production path (dbDeduct -> guardDeduct) is covered by RedisInventoryTest.
// No @Transactional: child threads use separate DB connections.
class ConcurrentInventoryTest extends IntegrationTestBase {

    // 200 threads vs 50 tickets: 4:1 contention ratio reliably triggers @Version conflicts.
    private static final int  THREADS        = 200;
    private static final int  TICKETS        = 50;
    //ID isolation: TICKET_TYPE_ID=1999L.
    private static final Long TICKET_TYPE_ID = 1999L;

    @Autowired private InventoryService    inventoryService;
    @Autowired private InventoryRepository inventoryRepository;

    @BeforeEach
    void setUp() {
        inventoryRepository.findByTicketTypeId(TICKET_TYPE_ID).ifPresent(inventoryRepository::delete);
        inventoryService.initStock(TICKET_TYPE_ID, TICKETS);
    }

    @AfterEach
    void tearDown() {
        inventoryRepository.findByTicketTypeId(TICKET_TYPE_ID).ifPresent(inventoryRepository::delete);
    }

    @Test
    void concurrent_deduction_with_optimistic_locking_zero_oversell() throws InterruptedException {
        AtomicInteger sold          = new AtomicInteger(0);
        AtomicInteger insufficient  = new AtomicInteger(0);
        AtomicInteger lockConflicts = new AtomicInteger(0);
        AtomicReference<Throwable> unexpectedError = new AtomicReference<>();

        CountDownLatch ready  = new CountDownLatch(THREADS);
        CountDownLatch start  = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(THREADS);

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        for (int i = 0; i < THREADS; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    DeductionResult result = inventoryService.deductStock(TICKET_TYPE_ID, 1);
                    if (result == DeductionResult.SUCCESS) sold.incrementAndGet();
                    else insufficient.incrementAndGet();
                } catch (ObjectOptimisticLockingFailureException e) {
                    // Expected under @Version: only one of two concurrent writers on version N wins.
                    // The production path (guardDeduct) never throws this.
                    lockConflicts.incrementAndGet();
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

        Inventory inventory = inventoryRepository.findByTicketTypeId(TICKET_TYPE_ID).orElseThrow();

        assertThat(inventory.getAvailableStock()).isGreaterThanOrEqualTo(0);
        assertThat(sold.get() + inventory.getAvailableStock()).isEqualTo(TICKETS);
        // All threads accounted for: no silent swallowing
        assertThat(sold.get() + insufficient.get() + lockConflicts.get()).isEqualTo(THREADS);
        assertThat(sold.get()).isGreaterThan(0);
    }
}