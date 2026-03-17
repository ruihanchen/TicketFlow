package com.chendev.ticketflow.integration;

import com.chendev.ticketflow.IntegrationTestBase;
import com.chendev.ticketflow.event.entity.Event;
import com.chendev.ticketflow.event.entity.TicketType;
import com.chendev.ticketflow.event.repository.EventRepository;
import com.chendev.ticketflow.event.repository.TicketTypeRepository;
import com.chendev.ticketflow.inventory.entity.Inventory;
import com.chendev.ticketflow.inventory.repository.InventoryRepository;
import com.chendev.ticketflow.order.dto.CreateOrderRequest;
import com.chendev.ticketflow.order.repository.OrderRepository;
import com.chendev.ticketflow.order.service.OrderService;
import com.chendev.ticketflow.user.entity.User;
import com.chendev.ticketflow.user.repository.UserRepository;
import com.chendev.ticketflow.common.exception.BizException;
import com.chendev.ticketflow.common.response.ResultCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test A: Concurrent Inventory Deduction
 *
 * Two test methods with distinct purposes:
 *
 * 1. correctness_noRetry — single-attempt correctness test.
 *    Each thread tries once and exits. No retry logic.
 *    Purpose: verify zero overselling regardless of contention.
 *    This is the Phase 1 → Phase 2 regression baseline:
 *    same assertions must pass with both InventoryAdapter (optimistic lock)
 *    and RedisInventoryAdapter (Lua script).
 *
 * 2. exhaustion_withRetry — persistent-user final-consistency test.
 *    Each thread retries on transient contention until stock is exhausted.
 *    Purpose: verify that all stock is eventually sold under Phase 1 locking.
 *    Phase 2 note: INVENTORY_LOCK_FAILED should never appear with Redis Lua,
 *    so the retry loop becomes a no-op — test still passes, but for a
 *    different reason (zero contention, not retry).
 */
class ConcurrentInventoryTest extends IntegrationTestBase {

    @Autowired private OrderService orderService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private InventoryRepository inventoryRepository;
    @Autowired private TicketTypeRepository ticketTypeRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private static final int TOTAL_STOCK  = 50;
    private static final int THREAD_COUNT = 200;

    private Long ticketTypeId;
    private List<Long> userIds;

    @BeforeEach
    void setUp() {
        // ← CHANGED: removed all deleteAll() calls.
        // IntegrationTestBase.cleanDatabase() already runs TRUNCATE ... RESTART IDENTITY CASCADE
        // before every test method. Having a second cleanup here was redundant and
        // created an implicit dependency on JUnit's @BeforeEach ordering (parent before child),
        // which is not guaranteed by the JUnit 5 specification.

        Event event = Event.create(
                "Phase 2 Test Concert",
                "Concurrent purchase stress test",
                "Test Venue",
                LocalDateTime.now().plusDays(30),
                LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusDays(29)
        );
        event.publish();
        event = eventRepository.save(event);

        TicketType ticketType = TicketType.create(
                event,
                "Standard",
                new BigDecimal("99.00"),
                TOTAL_STOCK
        );
        ticketType = ticketTypeRepository.save(ticketType);
        ticketTypeId = ticketType.getId();

        inventoryRepository.save(Inventory.initialize(ticketTypeId, TOTAL_STOCK));

        userIds = new ArrayList<>();
        for (int i = 0; i < THREAD_COUNT; i++) {
            User user = User.create(
                    "testuser_" + i,
                    "testuser" + i + "@test.com",
                    passwordEncoder.encode("password")
            );
            userIds.add(userRepository.save(user).getId());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test method 1: Correctness — single attempt, no retry
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Each of the 200 threads makes exactly ONE purchase attempt and exits.
     *
     * What this proves:
     *   - Zero overselling regardless of adapter (Phase 1 or Phase 2).
     *   - successCount is always <= TOTAL_STOCK.
     *   - successCount + failCount == THREAD_COUNT (no silent exceptions).
     *
     * Phase 1 behaviour: successCount will be less than TOTAL_STOCK because
     * threads that hit INVENTORY_LOCK_FAILED give up immediately. Some stock
     * may be left unsold.
     *
     * Phase 2 behaviour: With Redis Lua, there is no lock contention.
     * Exactly TOTAL_STOCK threads succeed, the rest get INSUFFICIENT_STOCK.
     * successCount == TOTAL_STOCK.
     *
     * Both phases must satisfy: overselling == 0.
     * Only Phase 2 must additionally satisfy: successCount == TOTAL_STOCK.
     */
    @Test
    void concurrentPurchase_noRetry_zeroOverselling() throws InterruptedException {
        // ← NEW: this test replaces the old single test method.
        // The old version had retry logic mixed in, which obscured the
        // difference between Phase 1 (lock contention causes retries) and
        // Phase 2 (no contention, no retries needed). Separating them makes
        // the before/after comparison unambiguous.

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount    = new AtomicInteger(0);

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);

        for (int i = 0; i < THREAD_COUNT; i++) {
            final Long userId = userIds.get(i);
            executor.submit(() -> {
                try {
                    startGate.await();
                    orderService.createOrder(userId,
                            CreateOrderRequest.forTest(ticketTypeId, 1,
                                    UUID.randomUUID().toString()));
                    successCount.incrementAndGet();
                } catch (BizException e) {
                    // Both INSUFFICIENT_STOCK and INVENTORY_LOCK_FAILED are
                    // expected business failures. Either means "did not buy".
                    failCount.incrementAndGet();
                } catch (Exception e) {
                    // Unexpected — surfaces real bugs
                    e.printStackTrace();
                    failCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startGate.countDown();
        doneLatch.await();
        executor.shutdown();

        int finalStock = inventoryRepository
                .findByTicketTypeId(ticketTypeId).orElseThrow()
                .getAvailableStock();

        // Core invariant: zero overselling, always.
        assertThat(finalStock)
                .as("available_stock must never go negative — zero overselling guaranteed")
                .isGreaterThanOrEqualTo(0);

        // Cross-check: DB order count must equal success counter.
        assertThat(orderRepository.count())
                .as("DB order count must match atomic success count — no phantom orders")
                .isEqualTo(successCount.get());

        // Stock accounting: sold + remaining == initial.
        assertThat(successCount.get() + finalStock)
                .as("tickets sold + remaining must equal initial stock")
                .isEqualTo(TOTAL_STOCK);

        // All threads must have resolved — no silent hangs.
        assertThat(successCount.get() + failCount.get())
                .as("Every thread must have either succeeded or explicitly failed")
                .isEqualTo(THREAD_COUNT);

        System.out.printf(
                "%n[Concurrent Test — no retry] threads=%d, stock=%d, " +
                        "sold=%d, failed=%d, remaining=%d%n",
                THREAD_COUNT, TOTAL_STOCK,
                successCount.get(), failCount.get(), finalStock);

        // ── Phase 2 gate (uncomment after RedisInventoryAdapter is active) ──
        // assertThat(successCount.get())
        //     .as("Phase 2: Redis Lua eliminates contention — all stock must sell in one pass")
        //     .isEqualTo(TOTAL_STOCK);
        //
        // assertThat(finalStock)
        //     .as("Phase 2: available_stock must be exactly 0")
        //     .isEqualTo(0);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Test method 2: Final consistency — persistent retry until exhaustion
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Each thread retries on transient contention (INVENTORY_LOCK_FAILED) until
     * it either succeeds or receives a definitive "sold out" response.
     *
     * What this proves:
     *   - All stock is eventually sold by persistent users — no tickets "stuck".
     *   - Optimistic lock contention does not cause permanent failure, only delay.
     *
     * Phase 1 behaviour: heavy WARN logs from OptimisticLockException, long duration
     * (~33s for 50 tickets across 200 threads). All 50 tickets eventually sold.
     *
     * Phase 2 behaviour: INVENTORY_LOCK_FAILED never fires. Retry loop exits on first
     * attempt for all threads. Test completes in under 3s. All 50 tickets still sold.
     * The retry logic becomes a no-op — same result, radically different path.
     *
     * This test is kept to document the Phase 1 baseline behaviour and to confirm
     * that Phase 2 produces the same final state via a different (contention-free) path.
     */
    @Test
    void concurrentPurchase_withRetry_allStockEventuallySold() throws InterruptedException {
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount    = new AtomicInteger(0);

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);

        for (int i = 0; i < THREAD_COUNT; i++) {
            final Long userId = userIds.get(i);
            executor.submit(() -> {
                try {
                    startGate.await();

                    boolean purchased = false;
                    while (!purchased) {
                        try {
                            orderService.createOrder(userId,
                                    CreateOrderRequest.forTest(ticketTypeId, 1,
                                            UUID.randomUUID().toString()));
                            successCount.incrementAndGet();
                            purchased = true;

                        } catch (BizException e) {
                            if (e.getCode() == ResultCode.INVENTORY_LOCK_FAILED.getCode()) {
                                // Transient contention. Retry after brief pause.
                                // Phase 2: this branch should never execute —
                                // Redis Lua is atomic, no lock conflicts possible.
                                Thread.sleep(10);
                            } else {
                                // INSUFFICIENT_STOCK or other definitive failure.
                                failCount.incrementAndGet();
                                purchased = true;
                            }
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    failCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startGate.countDown();
        doneLatch.await();
        executor.shutdown();

        // With retry, all stock must be sold.
        assertThat(successCount.get())
                .as("All stock must be sold — persistent users retry until success or sold out")
                .isEqualTo(TOTAL_STOCK);

        assertThat(orderRepository.count())
                .as("DB order count must match atomic success count")
                .isEqualTo(TOTAL_STOCK);

        assertThat(inventoryRepository.findByTicketTypeId(ticketTypeId).orElseThrow()
                .getAvailableStock())
                .as("available_stock must be exactly 0 after all stock is sold")
                .isEqualTo(0);

        System.out.printf(
                "%n[Concurrent Test — with retry] threads=%d, stock=%d, " +
                        "sold=%d, failed_sold_out=%d%n",
                THREAD_COUNT, TOTAL_STOCK, successCount.get(), failCount.get());
    }
}
