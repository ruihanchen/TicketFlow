package com.chendev.ticketflow.integration;

import com.chendev.ticketflow.IntegrationTestBase;
import com.chendev.ticketflow.common.exception.DomainException;
import com.chendev.ticketflow.common.response.ResultCode;
import com.chendev.ticketflow.event.entity.Event;
import com.chendev.ticketflow.event.entity.TicketType;
import com.chendev.ticketflow.event.repository.EventRepository;
import com.chendev.ticketflow.event.repository.TicketTypeRepository;
import com.chendev.ticketflow.inventory.repository.InventoryRepository;
import com.chendev.ticketflow.inventory.service.InventoryService;
import com.chendev.ticketflow.OrderTestFactory;
import com.chendev.ticketflow.order.dto.OrderResponse;
import com.chendev.ticketflow.order.repository.OrderRepository;
import com.chendev.ticketflow.order.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

// Idempotency contract for createOrder(): same requestId must always produce one order row, regardless of how many
// concurrent requests carry it. TX rollback in catch(DataIntegrityViolation) undoes any inventory deduction
//  from a losing thread. No @Transactional: child threads need to see committed DB state.
class IdempotencyTest extends IntegrationTestBase {

    @Autowired private OrderService         orderService;
    @Autowired private OrderRepository      orderRepository;
    @Autowired private EventRepository      eventRepository;
    @Autowired private TicketTypeRepository ticketTypeRepository;
    @Autowired private InventoryRepository  inventoryRepository;
    @Autowired private InventoryService     inventoryService;

    private static final Long       USER_ID        = 1L;
    private static final int        INITIAL_STOCK  = 10;
    // 50 threads: wide enough to reliably hit the TOCTOU window between existsByRequestId and INSERT.
    private static final int        CONCURRENT_THREADS = 50;
    private static final BigDecimal TICKET_PRICE   = new BigDecimal("100.00");

    private Long   ticketTypeId;
    private String requestId;

    @BeforeEach
    void setUp() {
        // Deletion order respects FK constraints: orders -> ticket_types -> events
        orderRepository.deleteAll();
        inventoryRepository.deleteAll();
        ticketTypeRepository.deleteAll();
        eventRepository.deleteAll();

        Instant now = Instant.now();
        Event event = Event.create("Test Event", "desc", "venue",
                now.plus(30, ChronoUnit.DAYS),
                now.minus(1, ChronoUnit.DAYS),
                now.plus(1, ChronoUnit.DAYS));
        event.publish();
        eventRepository.save(event);

        TicketType tt = TicketType.create(event, "General", TICKET_PRICE, INITIAL_STOCK);
        ticketTypeRepository.save(tt);
        ticketTypeId = tt.getId();

        inventoryService.initStock(ticketTypeId, INITIAL_STOCK);
        requestId = UUID.randomUUID().toString();
    }

    @Test
    void same_requestId_sequential_returns_same_orderNo_and_creates_one_row() {
        OrderResponse first  = orderService.createOrder(USER_ID,
                OrderTestFactory.createRequest(ticketTypeId, 1, requestId));
        OrderResponse second = orderService.createOrder(USER_ID,
                OrderTestFactory.createRequest(ticketTypeId, 1, requestId));

        assertThat(second.getOrderNo()).isEqualTo(first.getOrderNo());
        assertThat(orderRepository.count()).isEqualTo(1);
    }

    @Test
    void sequential_replay_does_not_deduct_stock_twice() {
        orderService.createOrder(USER_ID, OrderTestFactory.createRequest(ticketTypeId, 1, requestId));
        int stockAfterFirst = availableStock();

        // existsByRequestId returns true on replay; early return, no inventory call
        orderService.createOrder(USER_ID, OrderTestFactory.createRequest(ticketTypeId, 1, requestId));

        assertThat(availableStock()).isEqualTo(stockAfterFirst);
    }

    @Test
    void same_requestId_concurrent_creates_exactly_one_order() throws InterruptedException {
        CountDownLatch ready  = new CountDownLatch(CONCURRENT_THREADS);
        CountDownLatch start  = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(CONCURRENT_THREADS);

        AtomicInteger              success       = new AtomicInteger(0);
        AtomicInteger              duplicates    = new AtomicInteger(0);
        AtomicReference<Throwable> unexpectedErr = new AtomicReference<>();

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_THREADS);
        for (int i = 0; i < CONCURRENT_THREADS; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    orderService.createOrder(USER_ID,
                            OrderTestFactory.createRequest(ticketTypeId, 1, requestId));
                    success.incrementAndGet();
                } catch (DomainException e) {
                    if (e.getResultCode() == ResultCode.DUPLICATE_REQUEST) {
                        // Expected: thread passed existsByRequestId=false but lost the INSERT race.
                        // DB unique constraint fires, TX rolls back (undoing inventory deduction).
                        duplicates.incrementAndGet();
                    } else {
                        unexpectedErr.compareAndSet(null, e);
                    }
                } catch (Throwable t) {
                    unexpectedErr.compareAndSet(null, t);
                } finally {
                    finish.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        finish.await(15, TimeUnit.SECONDS);
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        assertThat(unexpectedErr.get()).isNull();
        assertThat(orderRepository.count()).isEqualTo(1);
        assertThat(availableStock()).isEqualTo(INITIAL_STOCK - 1);
        assertThat(success.get() + duplicates.get()).isEqualTo(CONCURRENT_THREADS);
        assertThat(success.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void different_requestIds_create_independent_orders() {
        String requestId2 = UUID.randomUUID().toString();
        orderService.createOrder(USER_ID, OrderTestFactory.createRequest(ticketTypeId, 1, requestId));
        orderService.createOrder(USER_ID, OrderTestFactory.createRequest(ticketTypeId, 1, requestId2));

        assertThat(orderRepository.count()).isEqualTo(2);
        assertThat(availableStock()).isEqualTo(INITIAL_STOCK - 2);
    }

    private int availableStock() {
        return inventoryRepository.findByTicketTypeId(ticketTypeId).orElseThrow().getAvailableStock();
    }
}