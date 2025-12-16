package com.chendev.ticketflow.integration;

import com.chendev.ticketflow.IntegrationTestBase;
import com.chendev.ticketflow.inventory.entity.Inventory;
import com.chendev.ticketflow.inventory.repository.InventoryRepository;
import com.chendev.ticketflow.inventory.service.InventoryService;
import com.chendev.ticketflow.order.port.InventoryPort.DeductionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

// no @Transactional: child threads use separate DB connections, cannot see uncommitted data from test transaction
class ConcurrentInventoryTest extends IntegrationTestBase {

    private static final int  THREADS        = 200;
    private static final int  TICKETS        = 50;
    private static final Long TICKET_TYPE_ID = 1999L;

    @Autowired private InventoryService    inventoryService;
    @Autowired private InventoryRepository inventoryRepository;

    @BeforeEach
    void setUp() {
        inventoryRepository.deleteAll();
        inventoryService.initStock(TICKET_TYPE_ID, TICKETS);
    }

    @Test
    void concurrent_deduction_zero_oversell() throws InterruptedException {
        AtomicInteger sold          = new AtomicInteger(0);
        AtomicInteger insufficient  = new AtomicInteger(0);
        AtomicInteger lockConflicts = new AtomicInteger(0);

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
                    if (result == DeductionResult.SUCCESS) {
                        sold.incrementAndGet();
                    } else if (result == DeductionResult.INSUFFICIENT) {
                        insufficient.incrementAndGet();
                    }
                } catch (ObjectOptimisticLockingFailureException e) {
                    // expected under @Version: only one thread wins per version increment
                    lockConflicts.incrementAndGet();
                } catch (Exception e) {
                    // unexpected: rethrow so the test fails with full context
                    throw new RuntimeException("unexpected error in deduct thread", e);
                } finally {
                    finish.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        finish.await();
        pool.shutdown();

        Inventory inventory = inventoryRepository
                .findByTicketTypeId(TICKET_TYPE_ID).orElseThrow();

        assertThat(inventory.getAvailableStock())
                .as("available stock must never go negative")
                .isGreaterThanOrEqualTo(0);

        assertThat(sold.get() + inventory.getAvailableStock())
                .as("sold + remaining must equal initial stock")
                .isEqualTo(TICKETS);

        assertThat(sold.get() + insufficient.get() + lockConflicts.get())
                .as("all threads must be accounted for")
                .isEqualTo(THREADS);

        // lock conflicts are @Version-dependent and not guaranteed on single-core CI;
        // correctness invariant is sold + remaining == TICKETS, not the conflict count
        assertThat(sold.get())
                .as("some tickets sold (exact count depends on scheduling)")
                .isGreaterThan(0);
    }
}