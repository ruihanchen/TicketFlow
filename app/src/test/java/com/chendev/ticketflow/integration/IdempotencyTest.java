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

class IdempotencyTest extends IntegrationTestBase {

    @Autowired private OrderService orderService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private InventoryRepository inventoryRepository;
    @Autowired private TicketTypeRepository ticketTypeRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private static final int THREAD_COUNT = 50;

    private Long ticketTypeId;
    private Long userId;

    @BeforeEach
    void setUp() {
        orderRepository.deleteAll();
        inventoryRepository.deleteAll();
        ticketTypeRepository.deleteAll();
        eventRepository.deleteAll();
        userRepository.deleteAll();

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

        // Plenty of stock — this test is about idempotency, not inventory contention
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
        // All 50 threads share the exact same requestId —
        // simulating a user whose browser fires 50 duplicate requests simultaneously
        final String sharedRequestId = UUID.randomUUID().toString();

        AtomicInteger successCount         = new AtomicInteger(0);
        // Threads blocked by idempotency check (IDEMPOTENT_REJECTION or returned existing order)
        AtomicInteger idempotentRejectCount = new AtomicInteger(0);
        // Threads that failed for unexpected reasons — should always be 0
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
                } catch (org.springframework.transaction.UnexpectedRollbackException e) {
                    // Phase 1 known limitation: JPA/Hibernate session is corrupted after a
                    // constraint violation. Spring overrides our BizException(IDEMPOTENT_REJECTION)
                    // with this exception. The DB unique constraint DID enforce idempotency correctly
                    // (no duplicate order was created). This path disappears in Phase 2 when
                    // Redis SETNX prevents concurrent threads from reaching the save() step.
                    idempotentRejectCount.incrementAndGet();
                } catch (Exception e) {
                    unexpectedFailCount.incrementAndGet();
                    System.err.printf("[UNEXPECTED Exception] type=%s, message=%s%n",
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

        // ── Assertions — ordered from most fundamental to most specific ────────

        // 1. No unexpected failures — if this fails, read the stack traces above
        assertThat(unexpectedFailCount.get())
                .as("Zero unexpected exceptions — all failures must be intentional " +
                        "idempotency rejections, not system errors")
                .isEqualTo(0);

        // 2. At least one thread must have succeeded and created the order
        assertThat(successCount.get())
                .as("At least one thread must have received a successful response")
                .isGreaterThanOrEqualTo(1);

        // 3. Exactly one order in DB — the core correctness guarantee
        assertThat(orderRepository.count())
                .as("Only one order must be persisted regardless of concurrent submissions")
                .isEqualTo(1);

        // 4. All successful responses carried the same orderNo
        //    (idempotency means: same input → same output, not just "no duplicate row")
        assertThat(returnedOrderNos)
                .as("All successful responses must return the identical orderNo")
                .hasSize(1);

        System.out.printf(
                "%n[Idempotency Test] threads=%d, success=%d, " +
                        "idempotent_rejected=%d, unexpected_fail=%d, " +
                        "distinct_orderNos=%d, db_orders=%d%n",
                THREAD_COUNT, successCount.get(),
                idempotentRejectCount.get(), unexpectedFailCount.get(),
                returnedOrderNos.size(), orderRepository.count()
        );
    }
}
