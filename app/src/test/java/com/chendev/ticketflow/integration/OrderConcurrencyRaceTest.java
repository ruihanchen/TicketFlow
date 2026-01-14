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
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 2 @Version guarantees: (1) concurrent saves on the same Order raise  ObjectOptimisticLockingFailureException,
// proves the version predicate is in the UPDATE; (2) when two reapers race the same expired order
// the second's stale save fails and inventory is released exactly once.
class OrderConcurrencyRaceTest extends IntegrationTestBase {

    @Autowired private OrderService         orderService;
    @Autowired private OrderRepository      orderRepository;
    @Autowired private InventoryRepository  inventoryRepository;
    @Autowired private EventRepository      eventRepository;
    @Autowired private TicketTypeRepository ticketTypeRepository;
    @Autowired private InventoryService     inventoryService;
    @Autowired private TransactionTemplate  transactionTemplate;

    private static final int  INITIAL_STOCK = 5;
    private static final Long USER_ID       = 1L;
    private Long ticketTypeId;

    @BeforeEach
    void setUp() {
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

        TicketType tt = TicketType.create(event, "General",
                new BigDecimal("100.00"), INITIAL_STOCK);
        ticketTypeRepository.save(tt);
        ticketTypeId = tt.getId();

        inventoryService.initStock(ticketTypeId, INITIAL_STOCK);
    }

    @Test
    void concurrent_modifications_to_same_order_throw_optimistic_lock_exception() {
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
    void second_reaper_with_stale_snapshot_fails_when_first_reaper_already_cancelled() {
        // Simulates two reaper instances picking up the same expired order.
        // First one wins; second's stale save must fail, inventory released exactly once.
        String orderNo = createAndExpireOrder();
        Long orderId = orderRepository.findByOrderNo(orderNo).orElseThrow().getId();

        assertThat(currentStock()).isEqualTo(INITIAL_STOCK - 1);

        Order reaperBSnapshot = loadDetachedWithHistory(orderId);
        Long versionAtLoad = reaperBSnapshot.getVersion();

        // Reaper A: production path re-loads internally, transitions, releases inventory
        orderService.cancelOrderBySystem(orderNo);

        // Reaper B: stale snapshot still has version N; DB now has N+1 -> zero rows matched
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(s -> {
            reaperBSnapshot.transitionTo(OrderStatus.CANCELLED,
                    OrderEvent.SYSTEM_TIMEOUT, "reaper B stale");
            orderRepository.saveAndFlush(reaperBSnapshot);
        })).isInstanceOf(ObjectOptimisticLockingFailureException.class);

        Order finalOrder = orderRepository.findByOrderNo(orderNo).orElseThrow();
        assertThat(finalOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(finalOrder.getVersion())
                .as("only reaper A's commit should have bumped the version")
                .isEqualTo(versionAtLoad + 1);
        assertThat(currentStock())
                .as("inventory released exactly once despite two reapers attempting cancel")
                .isEqualTo(INITIAL_STOCK);
    }

    // Loads an Order and force-initializes statusHistory inside the TX so the
    // returned detached entity is safe to call transitionTo() on.
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

    private String createOrder() {
        return orderService.createOrder(USER_ID,
                        CreateOrderRequest.forTest(ticketTypeId, 1, UUID.randomUUID().toString()))
                .getOrderNo();
    }

    private String createAndExpireOrder() {
        String orderNo = createOrder();
        transactionTemplate.executeWithoutResult(s -> {
            Order order = orderRepository.findByOrderNo(orderNo).orElseThrow();
            order.expireNow();
            orderRepository.save(order);
        });
        return orderNo;
    }
}