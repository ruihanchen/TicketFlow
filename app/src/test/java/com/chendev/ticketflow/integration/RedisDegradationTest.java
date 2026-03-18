package com.chendev.ticketflow.integration;

import com.chendev.ticketflow.IntegrationTestBase;
import com.chendev.ticketflow.common.exception.BizException;
import com.chendev.ticketflow.common.response.ResultCode;
import com.chendev.ticketflow.event.entity.Event;
import com.chendev.ticketflow.event.entity.TicketType;
import com.chendev.ticketflow.event.repository.EventRepository;
import com.chendev.ticketflow.event.repository.TicketTypeRepository;
import com.chendev.ticketflow.inventory.entity.Inventory;
import com.chendev.ticketflow.inventory.redis.RedisInventoryManager;
import com.chendev.ticketflow.inventory.repository.InventoryRepository;
import com.chendev.ticketflow.order.dto.CreateOrderRequest;
import com.chendev.ticketflow.order.repository.OrderRepository;
import com.chendev.ticketflow.order.service.OrderService;
import com.chendev.ticketflow.user.entity.User;
import com.chendev.ticketflow.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;

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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Validates the Redis degradation path under complete Redis failure.
 *
 * RedisInventoryManager is mocked to throw RedisConnectionFailureException
 * on every call. RedisInventoryAdapter catches the exception and delegates
 * to InventoryAdapter (DB optimistic locking). This test proves that:
 *
 *   1. The system remains fully functional when Redis is down.
 *   2. Zero overselling is guaranteed via DB optimistic locking.
 *   3. All stock is eventually sold by persistent users (retry on lock conflict).
 *
 * Why @MockBean on RedisInventoryManager, not RedisInventoryAdapter?
 * RedisInventoryAdapter is the subject under test — its fallback logic must
 * actually execute. Mocking RedisInventoryAdapter itself would bypass the
 * degradation path entirely and prove nothing.
 *
 * Note: @MockBean forces a new Spring ApplicationContext. This test runs in
 * isolation and does not share the context with other integration tests.
 * The extra startup cost is acceptable given the value of verifying the
 * fallback path.
 */
class RedisDegradationTest extends IntegrationTestBase {

    // Injecting a mock causes Redis Lua calls to throw RedisConnectionFailureException.
    // RedisInventoryAdapter catches Exception and delegates to InventoryAdapter.
    @MockBean
    private RedisInventoryManager redisInventoryManager;

    @Autowired private OrderService       orderService;
    @Autowired private OrderRepository    orderRepository;
    @Autowired private InventoryRepository inventoryRepository;
    @Autowired private TicketTypeRepository ticketTypeRepository;
    @Autowired private EventRepository    eventRepository;
    @Autowired private UserRepository     userRepository;
    @Autowired private PasswordEncoder    passwordEncoder;

    private static final int TOTAL_STOCK  = 50;
    private static final int THREAD_COUNT = 200;

    private Long       ticketTypeId;
    private List<Long> userIds;

    @BeforeEach
    void configureMockAndFixtures() {
        // Configure mock: all Redis operations fail with a connection error.
        // This simulates a complete Redis outage — the worst-case degradation scenario.
        when(redisInventoryManager.deductStock(anyLong(), anyInt()))
                .thenThrow(new RedisConnectionFailureException("Redis is down (test-injected fault)"));
        when(redisInventoryManager.releaseStock(anyLong(), anyInt()))
                .thenThrow(new RedisConnectionFailureException("Redis is down (test-injected fault)"));

        // Fixtures — same structure as ConcurrentInventoryTest.
        Event event = Event.create(
                "Degradation Test Concert",
                "Redis failure simulation",
                "Test Venue",
                LocalDateTime.now().plusDays(30),
                LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusDays(29)
        );
        event.publish();
        event = eventRepository.save(event);

        TicketType ticketType = TicketType.create(
                event, "Standard", new BigDecimal("99.00"), TOTAL_STOCK);
        ticketType = ticketTypeRepository.save(ticketType);
        ticketTypeId = ticketType.getId();

        inventoryRepository.save(Inventory.initialize(ticketTypeId, TOTAL_STOCK));

        userIds = new ArrayList<>();
        for (int i = 0; i < THREAD_COUNT; i++) {
            User user = User.create(
                    "degradation_user_" + i,
                    "degradation" + i + "@test.com",
                    passwordEncoder.encode("password"));
            userIds.add(userRepository.save(user).getId());
        }
    }

    /**
     * 200 threads compete for 50 tickets with Redis completely unavailable.
     *
     * Each thread retries on INVENTORY_LOCK_FAILED (DB optimistic lock conflict)
     * until it either succeeds or receives a definitive "sold out" response.
     *
     * Expected outcome:
     *   - Exactly 50 orders created (no overselling, no under-selling).
     *   - Zero 500 errors — all failures are handled business exceptions.
     *   - WARN logs visible in output showing Redis fallback activating.
     *
     * This directly answers the Phase 2 exit criterion:
     * Redis Down? → DB Fallback. Full features, zero overselling.
     */
    @Test
    void redisFailed_fallsBackToDb_zeroOverselling() throws InterruptedException {
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

                    boolean done = false;
                    while (!done) {
                        try {
                            orderService.createOrder(userId,
                                    CreateOrderRequest.forTest(ticketTypeId, 1,
                                            UUID.randomUUID().toString()));
                            successCount.incrementAndGet();
                            done = true;

                        } catch (BizException e) {
                            if (e.getCode() == ResultCode.INVENTORY_LOCK_FAILED.getCode()) {
                                // DB optimistic lock conflict — transient, retry.
                                // This is expected under fallback: InventoryAdapter
                                // surfaces lock contention as a retryable signal.
                                Thread.sleep(10);
                            } else {
                                // INVENTORY_INSUFFICIENT or other definitive rejection.
                                failCount.incrementAndGet();
                                done = true;
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

        // ── Assertions ────────────────────────────────────────────────────────

        // Core guarantee: all stock sold, zero overselling.
        assertThat(successCount.get())
                .as("Exactly TOTAL_STOCK orders must succeed under Redis degradation")
                .isEqualTo(TOTAL_STOCK);

        assertThat(orderRepository.count())
                .as("DB order count must match atomic success count — no phantom orders")
                .isEqualTo(TOTAL_STOCK);

        assertThat(inventoryRepository.findByTicketTypeId(ticketTypeId)
                .orElseThrow().getAvailableStock())
                .as("available_stock must be exactly 0 — no overselling, no under-selling")
                .isEqualTo(0);

        System.out.printf(
                "%n[Degradation Test] redis=DOWN, fallback=InventoryAdapter, "
                        + "threads=%d, stock=%d, sold=%d, rejected=%d%n",
                THREAD_COUNT, TOTAL_STOCK, successCount.get(), failCount.get());
    }
}
