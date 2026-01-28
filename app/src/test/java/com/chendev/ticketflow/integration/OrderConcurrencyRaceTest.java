package com.chendev.ticketflow.integration;

import com.chendev.ticketflow.IntegrationTestBase;
import com.chendev.ticketflow.event.entity.Event;
import com.chendev.ticketflow.event.entity.TicketType;
import com.chendev.ticketflow.event.repository.EventRepository;
import com.chendev.ticketflow.event.repository.TicketTypeRepository;
import com.chendev.ticketflow.inventory.repository.InventoryRepository;
import com.chendev.ticketflow.inventory.service.InventoryService;
import com.chendev.ticketflow.OrderTestFactory;
import com.chendev.ticketflow.order.entity.Order;
import com.chendev.ticketflow.order.repository.OrderRepository;
import com.chendev.ticketflow.order.service.OrderService;
import com.chendev.ticketflow.order.statemachine.OrderEvent;
import com.chendev.ticketflow.order.statemachine.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Concurrency tests for three mechanisms: @Version optimistic locking, SKIP LOCKED reaper
// coordination, and PAYING exclusion from the reaper WHERE clause.
class OrderConcurrencyRaceTest extends IntegrationTestBase {

    @Autowired private OrderService         orderService;
    @Autowired private OrderRepository      orderRepository;
    @Autowired private InventoryRepository  inventoryRepository;
    @Autowired private EventRepository      eventRepository;
    @Autowired private TicketTypeRepository ticketTypeRepository;
    @Autowired private InventoryService     inventoryService;
    @Autowired private TransactionTemplate  transactionTemplate;

    private static final int        INITIAL_STOCK          = 5;
    private static final Long       USER_ID                = 1L;
    private static final BigDecimal TICKET_PRICE           = new BigDecimal("100.00");
    private static final int        REAPER_PAGE_SIZE       = 10;
    private static final long       THREAD_JOIN_TIMEOUT_MS = 10_000;
    private static final long       LATCH_TIMEOUT_SECONDS  = 5;

    private Long ticketTypeId;

    @BeforeEach
    void setUp() {
        // Deletion order respects FK constraints: orders -> ticket_types -> events
        orderRepository.deleteAll();
        inventoryRepository.deleteAll();
        ticketTypeRepository.deleteAll();
        eventRepository.deleteAll();

        Instant now = Instant.now();
        Event event = Event.create("Race Test Event", "desc", "venue",
                now.plus(30, ChronoUnit.DAYS),
                now.minus(1, ChronoUnit.DAYS),
                now.plus(1, ChronoUnit.DAYS));
        event.publish();
        eventRepository.save(event);

        TicketType tt = TicketType.create(event, "General", TICKET_PRICE, INITIAL_STOCK);
        ticketTypeRepository.save(tt);
        ticketTypeId = tt.getId();

        inventoryService.initStock(ticketTypeId, INITIAL_STOCK);
    }

    @Test
    void concurrent_modifications_to_same_order_raise_optimistic_lock_exception() {
        String orderNo = createOrder();
        Long orderId   = orderRepository.findByOrderNo(orderNo).orElseThrow().getId();

        // Force-initialize statusHistory inside the TX; transitionTo() calls list.add() on it,
        // and a lazy proxy outside the TX throws LazyInitializationException before @Version fires.
        Order loadedByA = loadDetachedWithHistory(orderId);
        Order loadedByB = loadDetachedWithHistory(orderId);

        assertThat(loadedByA.getVersion()).isEqualTo(loadedByB.getVersion());

        // Transaction A commits: version N -> N+1
        transactionTemplate.executeWithoutResult(s -> {
            Order managed = orderRepository.findById(orderId).orElseThrow();
            managed.transitionTo(OrderStatus.PAYING, OrderEvent.INITIATE_PAYMENT, "A wins");
            orderRepository.saveAndFlush(managed);
        });

        // Transaction B holds version N; UPDATE WHERE version=N matches zero rows after A committed.
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(s -> {
            loadedByB.transitionTo(OrderStatus.CANCELLED, OrderEvent.SYSTEM_TIMEOUT, "B loses");
            orderRepository.saveAndFlush(loadedByB);
        })).isInstanceOf(ObjectOptimisticLockingFailureException.class);

        assertThat(orderRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAYING);
        assertThat(availableStock()).isEqualTo(INITIAL_STOCK - 1);
    }

    @Test
    void skip_locked_prevents_two_reapers_from_processing_the_same_order() throws Exception {
        createAndExpireCreatedOrder();

        CountDownLatch reaperALocked  = new CountDownLatch(1);
        CountDownLatch reaperBQueried = new CountDownLatch(1);
        AtomicInteger  reaperASaw     = new AtomicInteger(-1);
        AtomicInteger  reaperBSaw     = new AtomicInteger(-1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread reaperA = new Thread(() -> {
            try {
                transactionTemplate.executeWithoutResult(s -> {
                    List<Order> locked = orderRepository.findExpiredCreatedForUpdate(
                            Instant.now(), PageRequest.of(0, REAPER_PAGE_SIZE));
                    reaperASaw.set(locked.size());
                    reaperALocked.countDown();
                    try {
                        reaperBQueried.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                reaperALocked.countDown(); // always unblock B
            }
        }, "reaperA");

        Thread reaperB = new Thread(() -> {
            try {
                if (!reaperALocked.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    failure.compareAndSet(null, new AssertionError("reaperA never acquired lock"));
                    return;
                }
                transactionTemplate.executeWithoutResult(s -> {
                    List<Order> locked = orderRepository.findExpiredCreatedForUpdate(
                            Instant.now(), PageRequest.of(0, REAPER_PAGE_SIZE));
                    reaperBSaw.set(locked.size());
                });
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                reaperBQueried.countDown(); // always unblock A
            }
        }, "reaperB");

        reaperA.start();
        reaperB.start();
        reaperA.join(THREAD_JOIN_TIMEOUT_MS);
        reaperB.join(THREAD_JOIN_TIMEOUT_MS);

        assertThat(reaperA.isAlive()).isFalse();
        assertThat(reaperB.isAlive()).isFalse();
        if (failure.get() != null) throw new AssertionError("test thread failed", failure.get());

        assertThat(reaperASaw.get()).isEqualTo(1);
        assertThat(reaperBSaw.get())
                .as("reaper B must skip the locked row (SKIP LOCKED semantics)")
                .isZero();
    }

    @Test
    void paying_order_is_not_cancelled_by_reaper_even_when_cart_deadline_passed() {
        // findExpiredCreatedForUpdate() has WHERE status=CREATED hardcoded in JPQL.
        // A PAYING order is excluded regardless of expiredAt.
        String orderNo = createOrder();
        orderService.payOrder(USER_ID, orderNo);

        transactionTemplate.executeWithoutResult(s -> {
            Order order = orderRepository.findByOrderNo(orderNo).orElseThrow();
            order.expireNow();
            orderRepository.save(order);
        });

        int processed = 0;
        while (orderService.processOneExpiredOrder()) processed++;

        assertThat(processed).isZero();
        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAYING);
        assertThat(availableStock()).isEqualTo(INITIAL_STOCK - 1);
    }

    @Test
    void reaper_drains_expired_created_backlog_and_restores_all_stock() {
        String o1 = createAndExpireCreatedOrder();
        String o2 = createAndExpireCreatedOrder();
        String o3 = createAndExpireCreatedOrder();

        assertThat(availableStock()).isEqualTo(INITIAL_STOCK - 3);

        int processed = 0;
        while (orderService.processOneExpiredOrder()) processed++;

        assertThat(processed).isEqualTo(3);
        for (String orderNo : List.of(o1, o2, o3)) {
            assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                    .isEqualTo(OrderStatus.CANCELLED);
        }
        assertThat(availableStock()).isEqualTo(INITIAL_STOCK);
    }

    // Force-initializes statusHistory inside the TX so the detached entity is safe to call
    // transitionTo() on without triggering LazyInitializationException.
    private Order loadDetachedWithHistory(Long orderId) {
        return transactionTemplate.execute(s -> {
            Order o = orderRepository.findById(orderId).orElseThrow();
            o.getStatusHistory().size();
            return o;
        });
    }

    private int availableStock() {
        return inventoryRepository.findByTicketTypeId(ticketTypeId).orElseThrow().getAvailableStock();
    }

    private long currentExpiredCreatedCount() {
        return orderRepository.findAll().stream()
                .filter(o -> o.getStatus() == OrderStatus.CREATED)
                .filter(Order::isCartExpired)
                .count();
    }

    private String createOrder() {
        return orderService.createOrder(USER_ID,
                        OrderTestFactory.createRequest(ticketTypeId, 1, UUID.randomUUID().toString()))
                .getOrderNo();
    }

    private String createAndExpireCreatedOrder() {
        String orderNo = createOrder();
        transactionTemplate.executeWithoutResult(s -> {
            Order order = orderRepository.findByOrderNo(orderNo).orElseThrow();
            order.expireNow();
            orderRepository.save(order);
        });
        return orderNo;
    }
}