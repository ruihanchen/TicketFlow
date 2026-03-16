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
        // Isolation: each test starts from a clean slate
        orderRepository.deleteAll();
        inventoryRepository.deleteAll();
        ticketTypeRepository.deleteAll();
        eventRepository.deleteAll();
        userRepository.deleteAll();

        // Build a published event with an active sale window
        Event event = Event.create(
                "Phase 2 Test Concert",
                "Concurrent purchase stress test",
                "Test Venue",
                LocalDateTime.now().plusDays(30),
                LocalDateTime.now().minusHours(1),   // sale already started
                LocalDateTime.now().plusDays(29)      // sale not yet ended
        );
        event.publish();
        event = eventRepository.save(event);

        // One ticket type with exactly TOTAL_STOCK tickets available
        TicketType ticketType = TicketType.create(
                event,
                "Standard",
                new BigDecimal("99.00"),
                TOTAL_STOCK
        );
        ticketType = ticketTypeRepository.save(ticketType);
        ticketTypeId = ticketType.getId();

        // Initialize inventory — availableStock == totalStock at this point
        inventoryRepository.save(Inventory.initialize(ticketTypeId, TOTAL_STOCK));

        // One unique user per thread — avoids any per-user purchase limit interference
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

    @Test
    void concurrentPurchase_exactlyFiftySucceed_zeroOverselling() throws InterruptedException {
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount   = new AtomicInteger(0);

        // startGate holds all threads at the starting line until we fire the gun.
        // Without this, threads would start sequentially and reduce contention.
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(THREAD_COUNT);

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);

        for (int i = 0; i < THREAD_COUNT; i++) {
            final Long userId = userIds.get(i);
            executor.submit(() -> {
                try {
                    startGate.await(); // all threads wait here, then rush simultaneously

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
                                // Transient contention — another transaction won this round.
                                // Stock may still be available. Retry after a brief pause,
                                // simulating a user hammering the "Buy" button.
                                Thread.sleep(10);
                            } else {
                                // INVENTORY_INSUFFICIENT (30004) or any other business error —
                                // stock is genuinely exhausted or request is invalid. Stop retrying.
                                failCount.incrementAndGet();
                                purchased = true; // exit the while loop
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

        startGate.countDown(); // fire — all threads rush simultaneously
        doneLatch.await();     // wait until every thread has finished
        executor.shutdown();

        // ── Assertions ────────────────────────────────────────────────────────

        // With retry loops, every persistent user either buys or gets INVENTORY_INSUFFICIENT.
        // No stock should be left unsold — optimistic lock contention is no longer a throughput leak.
        assertThat(successCount.get())
                .as("All stock must be sold — persistent users retry until success or sold out")
                .isEqualTo(TOTAL_STOCK);

        // Cross-check: order rows in DB must match atomic success count
        assertThat(orderRepository.count())
                .as("DB order count must match success count — no phantom orders")
                .isEqualTo(TOTAL_STOCK);

        // The final inventory must be exactly zero — no overselling, no under-selling
        assertThat(inventoryRepository.findByTicketTypeId(ticketTypeId).orElseThrow()
                .getAvailableStock())
                .as("available_stock must be exactly 0 after all stock is sold")
                .isEqualTo(0);

        // Log baseline numbers for benchmark documentation
        System.out.printf(
                "%n[Phase 1 Baseline — with retry] threads=%d, stock=%d, " +
                        "sold=%d, failed_sold_out=%d%n",
                THREAD_COUNT, TOTAL_STOCK, successCount.get(), failCount.get()
        );
    }
}
