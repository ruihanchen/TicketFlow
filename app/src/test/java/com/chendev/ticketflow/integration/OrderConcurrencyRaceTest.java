package com.chendev.ticketflow.integration;

import com.chendev.ticketflow.IntegrationTestBase;
import com.chendev.ticketflow.event.entity.Event;
import com.chendev.ticketflow.event.entity.TicketType;
import com.chendev.ticketflow.event.repository.EventRepository;
import com.chendev.ticketflow.event.repository.TicketTypeRepository;
import com.chendev.ticketflow.inventory.repository.InventoryRepository;
import com.chendev.ticketflow.inventory.service.InventoryService;
import com.chendev.ticketflow.order.dto.CreateOrderRequest;
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

// Integration tests for the SKIP LOCKED per-order reaper: @Version mechanics,
// multi-instance coordination, PAYING-not-cancelled invariant, and backlog drain.
class OrderConcurrencyRaceTest extends IntegrationTestBase {

    @Autowired private OrderService         orderService;
    @Autowired private OrderRepository      orderRepository;
    @Autowired private InventoryRepository  inventoryRepository;
    @Autowired private EventRepository      eventRepository;
    @Autowired private TicketTypeRepository ticketTypeRepository;
    @Autowired private InventoryService     inventoryService;
    @Autowired private TransactionTemplate  transactionTemplate;

    private static final int        INITIAL_STOCK   = 5;
    private static final Long       USER_ID         = 1L;
    private static final BigDecimal TICKET_PRICE    = new BigDecimal("100.00");
    private static final int        REAPER_PAGE_SIZE = 10;  // larger than any batch in these tests

    private Long ticketTypeId;

    @BeforeEach
    void setUp() {
        // deletion order matters: FK constraints orders -> ticket_types -> events
        orderRepository.deleteAll();
        inventoryRepository.deleteAll();
        ticketTypeRepository.deleteAll();
        eventRepository.deleteAll();

        Instant now = Instant.now();
        Event event = Event.create(
                "Race Test Event", "desc", "venue",
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
    void at_version_concurrent_modifications_raise_optimistic_lock_exception() {
        String orderNo = createOrder();
        Long orderId = orderRepository.findByOrderNo(orderNo).orElseThrow().getId();

        // statusHistory must be initialized inside the TX, transitionTo() calls add() on it,
        // and a lazy proxy outside the TX throws LazyInitializationException before @Version fires
        Order loadedByA = loadDetachedWithHistory(orderId);
        Order loadedByB = loadDetachedWithHistory(orderId);

        assertThat(loadedByA.getVersion()).isEqualTo(loadedByB.getVersion());

        // Transaction A commits via a fresh managed entity
        transactionTemplate.executeWithoutResult(status -> {
            Order managed = orderRepository.findById(orderId).orElseThrow();
            managed.transitionTo(OrderStatus.PAYING, OrderEvent.INITIATE_PAYMENT, "A wins");
            orderRepository.saveAndFlush(managed);
        });

        // Transaction B's stale snapshot carries the old version; UPDATE WHERE version=N
        // matches zero rows after A committed version N+1
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            loadedByB.transitionTo(OrderStatus.CANCELLED, OrderEvent.SYSTEM_TIMEOUT, "B loses");
            orderRepository.saveAndFlush(loadedByB);
        })).isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    void skip_locked_second_reaper_skips_row_held_by_first_reaper() throws Exception {
        // While reaper A holds the lock, reaper B must skip the row (not block) and return empty.
        createAndExpireCreatedOrder();
        assertThat(currentExpiredCreatedCount()).isEqualTo(1);

        CountDownLatch reaperALocked   = new CountDownLatch(1);
        CountDownLatch reaperBQueried  = new CountDownLatch(1);
        AtomicInteger  reaperASawCount = new AtomicInteger(-1);
        AtomicInteger  reaperBSawCount = new AtomicInteger(-1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread reaperA = new Thread(() -> {
            try {
                transactionTemplate.executeWithoutResult(s -> {
                    List<Order> locked = orderRepository.findExpiredCreatedForUpdate(
                            Instant.now(), PageRequest.of(0, REAPER_PAGE_SIZE));
                    reaperASawCount.set(locked.size());
                    reaperALocked.countDown();

                    // hold the row lock until reaper B has run its query
                    try {
                        reaperBQueried.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    // TX commits on exit, releasing the lock
                });
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
                reaperALocked.countDown();  // release B even on failure so test doesn't hang
            }
        }, "reaperA");

        Thread reaperB = new Thread(() -> {
            try {
                if (!reaperALocked.await(5, TimeUnit.SECONDS)) {
                    failure.compareAndSet(null,
                            new AssertionError("reaperA never signaled lock acquisition"));
                    return;
                }
                transactionTemplate.executeWithoutResult(s -> {
                    List<Order> locked = orderRepository.findExpiredCreatedForUpdate(
                            Instant.now(), PageRequest.of(0, REAPER_PAGE_SIZE));
                    reaperBSawCount.set(locked.size());
                });
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                reaperBQueried.countDown();  // always unblock A
            }
        }, "reaperB");

        reaperA.start();
        reaperB.start();
        reaperA.join(10_000);
        reaperB.join(10_000);

        if (failure.get() != null) {
            throw new AssertionError("test thread failed", failure.get());
        }

        assertThat(reaperASawCount.get())
                .as("reaper A should have acquired the lock on the one expired CREATED order")
                .isEqualTo(1);
        assertThat(reaperBSawCount.get())
                .as("reaper B should have skipped the row locked by reaper A and seen nothing")
                .isZero();
    }

    @Test
    void payment_in_progress_order_is_not_cancelled_even_when_expired() {
        // Scenario: user clicks pay just before the cart deadline; reaper polls afterward.
        // Reaper's WHERE clause is hardcoded to CREATED only -- PAYING orders are never touched.
        String orderNo = createOrder();
        orderService.payOrder(USER_ID, orderNo);  // CREATED -> PAYING

        // expire the cart deadline of the now-PAYING order; reaper must still skip it
        transactionTemplate.executeWithoutResult(s -> {
            Order order = orderRepository.findByOrderNo(orderNo).orElseThrow();
            order.expireNow();
            orderRepository.save(order);
        });

        int processed = 0;
        while (orderService.processOneExpiredOrder()) {
            processed++;
        }

        assertThat(processed)
                .as("reaper must not process any PAYING order, even when cart deadline passed")
                .isZero();
        Order finalOrder = orderRepository.findByOrderNo(orderNo).orElseThrow();
        assertThat(finalOrder.getStatus())
                .as("PAYING order with payment in flight must remain PAYING")
                .isEqualTo(OrderStatus.PAYING);
        assertThat(currentStock())
                .as("inventory must remain held while user is paying")
                .isEqualTo(INITIAL_STOCK - 1);
    }

    @Test
    void reaper_drains_expired_created_backlog_one_order_per_transaction() {
        String o1 = createAndExpireCreatedOrder();
        String o2 = createAndExpireCreatedOrder();
        String o3 = createAndExpireCreatedOrder();

        assertThat(currentStock()).isEqualTo(INITIAL_STOCK - 3);

        int processed = 0;
        while (orderService.processOneExpiredOrder()) {
            processed++;
        }

        assertThat(processed).isEqualTo(3);
        for (String orderNo : List.of(o1, o2, o3)) {
            assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                    .isEqualTo(OrderStatus.CANCELLED);
        }
        assertThat(currentStock())
                .as("all three reservations released back to pool")
                .isEqualTo(INITIAL_STOCK);
    }

    // force-initialize statusHistory inside the TX; transitionTo() calls add() on it,
    // and a lazy proxy outside the TX throws LazyInitializationException before @Version fires
    private Order loadDetachedWithHistory(Long orderId) {
        return transactionTemplate.execute(s -> {
            Order o = orderRepository.findById(orderId).orElseThrow();
            o.getStatusHistory().size();
            return o;
        });
    }

    private int currentStock() {
        return inventoryRepository.findByTicketTypeId(ticketTypeId)
                .orElseThrow().getAvailableStock();
    }

    private long currentExpiredCreatedCount() {
        return orderRepository.findAll().stream()
                .filter(o -> o.getStatus() == OrderStatus.CREATED)
                .filter(Order::isCartExpired)
                .count();
    }

    private String createOrder() {
        return orderService.createOrder(USER_ID,
                        CreateOrderRequest.forTest(ticketTypeId, 1, UUID.randomUUID().toString()))
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