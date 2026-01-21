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
import com.chendev.ticketflow.order.service.OrderTimeoutService;
import com.chendev.ticketflow.order.statemachine.OrderStatus;
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

// no @Transactional: OrderTimeoutService reads committed data to find expired orders;
// a test-level transaction would hide those commits from the reaper
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
                "Timeout Test Event", "desc", "venue",
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
    void expired_order_gets_cancelled_and_stock_restored() {
        String orderNo = createAndExpireOrder();

        orderTimeoutService.cancelExpiredOrders();

        Order cancelled = orderRepository.findByOrderNo(orderNo).orElseThrow();
        assertThat(cancelled.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(inventoryRepository.findByTicketTypeId(ticketTypeId)
                .orElseThrow().getAvailableStock()).isEqualTo(INITIAL_STOCK);
    }

    @Test
    void active_order_is_not_cancelled() {
        String orderNo = orderService.createOrder(USER_ID,
                        CreateOrderRequest.forTest(ticketTypeId, 1, UUID.randomUUID().toString()))
                .getOrderNo();

        orderTimeoutService.cancelExpiredOrders();

        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CREATED);
        assertThat(inventoryRepository.findByTicketTypeId(ticketTypeId)
                .orElseThrow().getAvailableStock()).isEqualTo(INITIAL_STOCK - 1);
    }

    @Test
    void timeout_reaper_is_idempotent() {
        String orderNo = createAndExpireOrder();

        // second pass is a no-op: CANCELLED orders are excluded from the WHERE clause
        orderTimeoutService.cancelExpiredOrders();
        orderTimeoutService.cancelExpiredOrders();

        long cancelledCount = orderRepository.findAll().stream()
                .filter(o -> o.getStatus() == OrderStatus.CANCELLED)
                .count();
        assertThat(cancelledCount).isEqualTo(1);
        assertThat(inventoryRepository.findByTicketTypeId(ticketTypeId)
                .orElseThrow().getAvailableStock()).isEqualTo(INITIAL_STOCK);
    }

    @Test
    void reaper_cancelled_counter_increments_per_order() {
        double before = counterValue("ticketflow_order_reaper_cancelled_total");

        String orderNo = createAndExpireOrder();
        orderTimeoutService.cancelExpiredOrders();

        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);
        assertThat(counterValue("ticketflow_order_reaper_cancelled_total") - before)
                .as("cancelled counter must increment once per successfully cancelled order")
                .isEqualTo(1.0);
    }

    @Test
    void reaper_failure_counter_increments_on_per_order_exception() {
        // delete inventory so releaseStock throws INVENTORY_NOT_FOUND, simulates data inconsistency
        String orderNo = createAndExpireOrder();
        inventoryRepository.deleteAll();  // nuke inventory so releaseStock throws

        // cap at 1 so the reaper doesn't retry the same broken order maxPerCycle times
        int original = overrideMaxPerCycle(1);
        try {
            double before = counterValue("ticketflow_order_reaper_failures_total");

            orderTimeoutService.cancelExpiredOrders();

            assertThat(counterValue("ticketflow_order_reaper_failures_total") - before)
                    .as("failure counter must increment when processOneExpiredOrder throws")
                    .isGreaterThanOrEqualTo(1.0);

            // order stays CREATED because the TX rolled back on exception
            assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                    .isEqualTo(OrderStatus.CREATED);
        } finally {
            overrideMaxPerCycle(original);
        }
    }

    @Test
    void reaper_saturation_counter_fires_when_backlog_exceeds_max_per_cycle() {
        // 3 expired orders, cap at 2, reaper must hit the ceiling and fire the saturation counter
        createAndExpireOrder();
        createAndExpireOrder();
        createAndExpireOrder();

        int original = overrideMaxPerCycle(2);
        try {
            double beforeSaturation = counterValue("ticketflow_order_reaper_cycles_saturated_total");
            double beforeCancelled  = counterValue("ticketflow_order_reaper_cancelled_total");

            orderTimeoutService.cancelExpiredOrders();

            assertThat(counterValue("ticketflow_order_reaper_cycles_saturated_total") - beforeSaturation)
                    .as("saturation counter must fire when loop runs to maxPerCycle without exhausting backlog")
                    .isEqualTo(1.0);
            assertThat(counterValue("ticketflow_order_reaper_cancelled_total") - beforeCancelled)
                    .as("only maxPerCycle orders should be cancelled in one cycle")
                    .isEqualTo(2.0);

            long remainingCreated = orderRepository.findAll().stream()
                    .filter(o -> o.getStatus() == OrderStatus.CREATED)
                    .count();
            assertThat(remainingCreated)
                    .as("1 expired order should remain for the next cycle")
                    .isEqualTo(1);
        } finally {
            overrideMaxPerCycle(original);
        }
    }

    // ---- helpers ----

    private String createAndExpireOrder() {
        String orderNo = orderService.createOrder(USER_ID,
                        CreateOrderRequest.forTest(ticketTypeId, 1, UUID.randomUUID().toString()))
                .getOrderNo();
        Order order = orderRepository.findByOrderNo(orderNo).orElseThrow();
        order.expireNow();
        orderRepository.save(order);
        return orderNo;
    }

    private double counterValue(String name) {
        return meterRegistry.find(name).counter().count();
    }

    // ReflectionTestUtils is the standard Spring test utility for overriding @Value fields without a setter
    private int overrideMaxPerCycle(int value) {
        int previous = (int) ReflectionTestUtils.getField(orderTimeoutService, "maxPerCycle");
        ReflectionTestUtils.setField(orderTimeoutService, "maxPerCycle", value);
        return previous;
    }
}