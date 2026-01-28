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
import com.chendev.ticketflow.order.service.OrderTimeoutService;
import com.chendev.ticketflow.order.statemachine.OrderStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Tests OrderTimeoutService.cancelExpiredOrders(). The reaper only processes CREATED orders;
// PAYING is hardcoded out of findExpiredCreatedForUpdate(). No @Transactional: the reaper
// reads committed data, so a test-level TX would hide commits from it.
class OrderTimeoutTest extends IntegrationTestBase {

    @Autowired private OrderService         orderService;
    @Autowired private OrderTimeoutService  orderTimeoutService;
    @Autowired private OrderRepository      orderRepository;
    @Autowired private InventoryRepository  inventoryRepository;
    @Autowired private EventRepository      eventRepository;
    @Autowired private TicketTypeRepository ticketTypeRepository;
    @Autowired private InventoryService     inventoryService;
    @Autowired private MeterRegistry        meterRegistry;

    private static final int        INITIAL_STOCK = 5;
    private static final Long       USER_ID       = 1L;
    private static final BigDecimal TICKET_PRICE  = new BigDecimal("100.00");

    // Names must match Counter.builder() strings in OrderTimeoutService.
    // If a name changes, SmokeTest.prometheus_endpoint will fail first.
    private static final String CANCELLED_METRIC = "ticketflow_order_reaper_cancelled_total";
    private static final String FAILURES_METRIC  = "ticketflow_order_reaper_failures_total";
    private static final String SATURATED_METRIC = "ticketflow_order_reaper_cycles_saturated_total";

    private Long ticketTypeId;

    @BeforeEach
    void setUp() {
        // Deletion order respects FK constraints: orders -> ticket_types -> events
        orderRepository.deleteAll();
        inventoryRepository.deleteAll();
        ticketTypeRepository.deleteAll();
        eventRepository.deleteAll();

        Instant now = Instant.now();
        Event event = Event.create("Timeout Test Event", "desc", "venue",
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
    void expired_created_order_is_cancelled_and_stock_restored() {
        String orderNo = createAndExpireOrder();
        orderTimeoutService.cancelExpiredOrders();

        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
        assertThat(availableStock()).isEqualTo(INITIAL_STOCK);
    }

    @Test
    void non_expired_created_order_is_not_cancelled() {
        String orderNo = orderService.createOrder(USER_ID,
                        OrderTestFactory.createRequest(ticketTypeId, 1, UUID.randomUUID().toString()))
                .getOrderNo();
        orderTimeoutService.cancelExpiredOrders();

        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CREATED);
        assertThat(availableStock()).isEqualTo(INITIAL_STOCK - 1);
    }

    @Test
    void paying_order_with_expired_cart_is_not_cancelled_by_reaper() {
        // findExpiredCreatedForUpdate() is hardcoded to status=CREATED, PAYING is never returned.
        String orderNo = createOrder();
        orderService.payOrder(USER_ID, orderNo);

        Order order = orderRepository.findByOrderNo(orderNo).orElseThrow();
        order.expireNow();
        orderRepository.save(order);

        orderTimeoutService.cancelExpiredOrders();

        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAYING);
        assertThat(availableStock()).isEqualTo(INITIAL_STOCK - 1);
    }

    @Test
    void reaper_is_idempotent_second_run_is_a_no_op() {
        // CANCELLED orders are excluded by WHERE status=CREATED; second run finds nothing.
        String orderNo = createAndExpireOrder();
        orderTimeoutService.cancelExpiredOrders();
        orderTimeoutService.cancelExpiredOrders();

        assertThat(orderRepository.findAll().stream()
                .filter(o -> o.getStatus() == OrderStatus.CANCELLED).count())
                .isEqualTo(1);
        assertThat(availableStock()).isEqualTo(INITIAL_STOCK);
    }

    @Test
    void cancelled_counter_increments_once_per_successfully_cancelled_order() {
        double before = counterValue(CANCELLED_METRIC);
        String orderNo = createAndExpireOrder();
        orderTimeoutService.cancelExpiredOrders();

        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
        assertThat(counterValue(CANCELLED_METRIC) - before).isEqualTo(1.0);
    }

    @Test
    void failure_counter_increments_when_processOneExpiredOrder_throws() {
        // Delete inventory so releaseStock() throws INVENTORY_NOT_FOUND.
        // Cap at 1 so the reaper doesn't retry the same broken order maxPerCycle times.
        String orderNo = createAndExpireOrder();
        inventoryRepository.deleteAll();

        int original = overrideMaxPerCycle(1);
        try {
            double before = counterValue(FAILURES_METRIC);
            orderTimeoutService.cancelExpiredOrders();

            assertThat(counterValue(FAILURES_METRIC) - before).isGreaterThanOrEqualTo(1.0);
            // TX rolled back: order stays CREATED
            assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                    .isEqualTo(OrderStatus.CREATED);
        } finally {
            overrideMaxPerCycle(original);
        }
    }

    @Test
    void saturation_counter_fires_when_backlog_exceeds_max_per_cycle() {
        // 3 expired orders, maxPerCycle=2: loop cancels 2, exits without exhausting backlog.
        createAndExpireOrder();
        createAndExpireOrder();
        createAndExpireOrder();

        int original = overrideMaxPerCycle(2);
        try {
            double beforeSat = counterValue(SATURATED_METRIC);
            double beforeCan = counterValue(CANCELLED_METRIC);

            orderTimeoutService.cancelExpiredOrders();

            assertThat(counterValue(SATURATED_METRIC) - beforeSat).isEqualTo(1.0);
            assertThat(counterValue(CANCELLED_METRIC) - beforeCan).isEqualTo(2.0);
            assertThat(orderRepository.findAll().stream()
                    .filter(o -> o.getStatus() == OrderStatus.CREATED).count())
                    .isEqualTo(1);
        } finally {
            overrideMaxPerCycle(original);
        }
    }

    private String createOrder() {
        return orderService.createOrder(USER_ID,
                        OrderTestFactory.createRequest(ticketTypeId, 1, UUID.randomUUID().toString()))
                .getOrderNo();
    }

    private String createAndExpireOrder() {
        String orderNo = createOrder();
        Order order = orderRepository.findByOrderNo(orderNo).orElseThrow();
        order.expireNow();
        orderRepository.save(order);
        return orderNo;
    }

    private int availableStock() {
        return inventoryRepository.findByTicketTypeId(ticketTypeId).orElseThrow().getAvailableStock();
    }

    private double counterValue(String name) {
        Counter c = meterRegistry.find(name).counter();
        return c == null ? 0.0 : c.count();
    }

    // ReflectionTestUtils is the standard Spring utility for overriding @Value fields without a setter.
    private int overrideMaxPerCycle(int value) {
        int previous = (int) ReflectionTestUtils.getField(orderTimeoutService, "maxPerCycle");
        ReflectionTestUtils.setField(orderTimeoutService, "maxPerCycle", value);
        return previous;
    }
}