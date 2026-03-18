package com.chendev.ticketflow.integration;

import com.chendev.ticketflow.IntegrationTestBase;
import com.chendev.ticketflow.common.exception.BizException;
import com.chendev.ticketflow.common.response.ResultCode;
import com.chendev.ticketflow.event.entity.Event;
import com.chendev.ticketflow.event.entity.TicketType;
import com.chendev.ticketflow.event.repository.EventRepository;
import com.chendev.ticketflow.event.repository.TicketTypeRepository;
import com.chendev.ticketflow.inventory.entity.Inventory;
import com.chendev.ticketflow.inventory.repository.InventoryRepository;
import com.chendev.ticketflow.order.dto.CreateOrderRequest;
import com.chendev.ticketflow.order.dto.OrderResponse;
import com.chendev.ticketflow.order.repository.OrderRepository;
import com.chendev.ticketflow.order.service.OrderService;
import com.chendev.ticketflow.user.entity.User;
import com.chendev.ticketflow.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test B: Idempotency
 *
 * Validates two distinct idempotency scenarios:
 *
 *   1. Concurrent duplicates: 50 threads fire the same requestId simultaneously.
 *      Phase 1 behaviour: session poisoning caused UnexpectedRollbackException (HTTP 500).
 *      Phase 2 behaviour: Redis SETNX fast-fails all duplicates with IDEMPOTENT_REJECTION
 *      (HTTP 400). Zero 500 errors. The core architectural improvement.
 *
 *   2. Sequential duplicate: after all concurrent threads complete, a fresh request
 *      is sent with the same requestId. Redis key now holds the orderNo (24h TTL).
 *      Expected: immediate cache hit, same orderNo returned, zero DB writes.
 *
 * Why successCount >= 2 is the wrong assertion for concurrent duplicates:
 *   Under nanosecond-level concurrency, threads 2-50 arrive while thread 1 is still
 *   in-flight (PROCESSING state). They are fast-failed immediately — this is correct
 *   Fail-Fast behaviour that preserves Tomcat thread pool capacity. Expecting >= 2
 *   "cache hits" in the concurrent phase assumes a timing that is not guaranteed and
 *   would require Thread.sleep() in the server code — a production anti-pattern that
 *   can exhaust the thread pool under load.
 *
 *   The real Phase 2 value is not "more threads succeed concurrently" but rather
 *   "zero threads receive a 500 Internal Server Error". That is what we assert.
 */
class IdempotencyTest extends IntegrationTestBase {

    @Autowired private OrderService         orderService;
    @Autowired private OrderRepository      orderRepository;
    @Autowired private InventoryRepository  inventoryRepository;
    @Autowired private TicketTypeRepository ticketTypeRepository;
    @Autowired private EventRepository      eventRepository;
    @Autowired private UserRepository       userRepository;
    @Autowired private PasswordEncoder      passwordEncoder;

    private static final int THREAD_COUNT = 50;

    private Long ticketTypeId;
    private Long userId;

    @BeforeEach
    void setUp() {
        // IntegrationTestBase.cleanDatabase() handles TRUNCATE + Redis FLUSHDB.

        Event event = Event.create(
                "Idempotency Test Concert",
                "Test",
                "Test Venue",
                LocalDateTime.now().plusDays(30),
                LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusDays(29)
        );
        event.publish();
        event = eventRepository.save(event);

        TicketType ticketType = TicketType.create(
                event, "Standard", new BigDecimal("99.00"), 100
        );
        ticketType = ticketTypeRepository.save(ticketType);
        ticketTypeId = ticketType.getId();

        inventoryRepository.save(Inventory.initialize(ticketTypeId, 100));

        User user = User.create(
                "idempotency_user",
                "idempotency@test.com",
                passwordEncoder.encode("password")
        );
        userId = userRepository.save(user).getId();
    }

    @Test
    void sameRequestId_concurrentSubmissions_onlyOneOrderCreated() throws InterruptedException {

        final String sharedRequestId = UUID.randomUUID().toString();

        AtomicInteger successCount          = new AtomicInteger(0);
        AtomicInteger idempotentRejectCount = new AtomicInteger(0);
        AtomicInteger unexpectedFailCount   = new AtomicInteger(0);

        Set<String> returnedOrderNos = ConcurrentHashMap.newKeySet();

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);

        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    OrderResponse response = orderService.createOrder(userId,
                            CreateOrderRequest.forTest(ticketTypeId, 1, sharedRequestId));
                    successCount.incrementAndGet();
                    returnedOrderNos.add(response.getOrderNo());

                } catch (BizException e) {
                    if (e.getCode() == ResultCode.IDEMPOTENT_REJECTION.getCode()
                            || e.getCode() == ResultCode.INVENTORY_LOCK_FAILED.getCode()) {
                        idempotentRejectCount.incrementAndGet();
                    } else {
                        unexpectedFailCount.incrementAndGet();
                        e.printStackTrace();
                    }
                } catch (Exception e) {
                    // Phase 2: UnexpectedRollbackException must not appear here.
                    // Redis SETNX prevents concurrent threads from reaching save(),
                    // so Hibernate session poisoning cannot occur.
                    // Any exception landing here is a genuine unexpected failure.
                    unexpectedFailCount.incrementAndGet();
                    System.err.printf("[UNEXPECTED] type=%s, message=%s%n",
                            e.getClass().getSimpleName(), e.getMessage());
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startGate.countDown();
        doneLatch.await();
        executor.shutdown();

        // ── Concurrent duplicate assertions ───────────────────────────────────

        // Phase 2 core guarantee: zero 500 errors.
        // Phase 1 had UnexpectedRollbackException (HTTP 500) from Hibernate session
        // poisoning. Redis SETNX eliminates this by preventing concurrent threads
        // from reaching the DB save() step entirely.
        assertThat(unexpectedFailCount.get())
                .as("Phase 2 core guarantee: zero unexpected exceptions. "
                        + "Phase 1 had UnexpectedRollbackException here.")
                .isEqualTo(0);

        // Exactly one order in DB.
        assertThat(orderRepository.count())
                .as("Only one order must be persisted regardless of concurrent submissions")
                .isEqualTo(1);

        // At least one thread succeeded.
        assertThat(successCount.get())
                .as("At least one thread must receive a successful response")
                .isGreaterThanOrEqualTo(1);

        // All successful responses carried the same orderNo.
        assertThat(returnedOrderNos)
                .as("All successful responses must return the identical orderNo")
                .hasSize(1);

        // ── Sequential duplicate assertion ────────────────────────────────────
        //
        // All 50 concurrent threads have now finished. Thread 1 has called
        // markIdempotencyCompleted(), so the Redis key holds the real orderNo
        // with a 24h TTL.
        //
        // This models the real-world "user retries 5 minutes later" scenario:
        //   - Network timeout, user clicks Buy again.
        //   - Redis cache hit: same orderNo returned immediately.
        //   - No inventory deducted again. No new order row in DB.
        //
        // This is the other half of idempotency that the concurrent test cannot
        // cover, because in the concurrent phase the key is still PROCESSING.
        String firstOrderNo = returnedOrderNos.iterator().next();

        OrderResponse sequentialDuplicate = orderService.createOrder(userId,
                CreateOrderRequest.forTest(ticketTypeId, 1, sharedRequestId));

        assertThat(sequentialDuplicate.getOrderNo())
                .as("Sequential duplicate must return the cached orderNo from Redis — "
                        + "not create a new order")
                .isEqualTo(firstOrderNo);

        assertThat(orderRepository.count())
                .as("Sequential duplicate must not create a new order in DB")
                .isEqualTo(1);

        System.out.printf(
                "%n[Idempotency Test] threads=%d, success=%d, "
                        + "idempotent_rejected=%d, unexpected_fail=%d, "
                        + "distinct_orderNos=%d, db_orders=%d%n"
                        + "[Sequential duplicate] cached orderNo=%s (matches original: %b)%n",
                THREAD_COUNT, successCount.get(),
                idempotentRejectCount.get(), unexpectedFailCount.get(),
                returnedOrderNos.size(), orderRepository.count(),
                sequentialDuplicate.getOrderNo(),
                sequentialDuplicate.getOrderNo().equals(firstOrderNo));
    }
}
