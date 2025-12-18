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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

//no @Transactional:OrderTimeoutService reads committed data to find expired orders,test level transaction
//would hide those commits from the reaper
class OrderTimeoutTest extends IntegrationTestBase {

    @Autowired private OrderService         orderService;
    @Autowired private OrderTimeoutService  orderTimeoutService;
    @Autowired private OrderRepository      orderRepository;
    @Autowired private InventoryRepository  inventoryRepository;
    @Autowired private EventRepository      eventRepository;
    @Autowired private TicketTypeRepository ticketTypeRepository;
    @Autowired private InventoryService     inventoryService;

    private static final int  INITIAL_STOCK = 5;
    private static final Long USER_ID       = 1L;
    private Long ticketTypeId;

    @BeforeEach
    void setUp() {
        // deletion order matters:FK constraints: orders→ticket_types→events
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

        TicketType tt = TicketType.create(event, "General",
                new BigDecimal("100.00"), INITIAL_STOCK);
        ticketTypeRepository.save(tt);
        ticketTypeId = tt.getId();

        inventoryService.initStock(ticketTypeId, INITIAL_STOCK);
    }

    @Test
    void expired_order_gets_cancelled_and_stock_restored() {
        String orderNo = orderService.createOrder(USER_ID,
                        CreateOrderRequest.forTest(ticketTypeId, 1, UUID.randomUUID().toString()))
                .getOrderNo();

        Order order = orderRepository.findByOrderNo(orderNo).orElseThrow();
        order.expireNow();
        orderRepository.save(order);

        orderTimeoutService.cancelExpiredOrders();

        Order cancelled = orderRepository.findByOrderNo(orderNo).orElseThrow();
        assertThat(cancelled.getStatus()).isEqualTo(OrderStatus.CANCELLED);

        int stock = inventoryRepository.findByTicketTypeId(ticketTypeId)
                .orElseThrow().getAvailableStock();
        assertThat(stock).isEqualTo(INITIAL_STOCK);

        System.out.printf("%n[OrderTimeoutTest] status=%s, stock=%d (expected=%d)%n",
                cancelled.getStatus(), stock, INITIAL_STOCK);
    }

    @Test
    void active_order_is_not_cancelled() {
        String orderNo = orderService.createOrder(USER_ID,
                        CreateOrderRequest.forTest(ticketTypeId, 1, UUID.randomUUID().toString()))
                .getOrderNo();

        orderTimeoutService.cancelExpiredOrders();

        Order order = orderRepository.findByOrderNo(orderNo).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(inventoryRepository.findByTicketTypeId(ticketTypeId)
                .orElseThrow().getAvailableStock()).isEqualTo(INITIAL_STOCK - 1);
    }

    @Test
    void timeout_reaper_is_idempotent() {
        String orderNo = orderService.createOrder(USER_ID,
                        CreateOrderRequest.forTest(ticketTypeId, 1, UUID.randomUUID().toString()))
                .getOrderNo();

        Order order = orderRepository.findByOrderNo(orderNo).orElseThrow();
        order.expireNow();
        orderRepository.save(order);

        // second pass should be a no-op: the first cancellation transitions to CANCELLED,and the timeout query
        // excludes CANCELLED orders via the WHERE clause.
        orderTimeoutService.cancelExpiredOrders();
        orderTimeoutService.cancelExpiredOrders();

        long cancelledCount = orderRepository.findAll().stream()
                .filter(o -> o.getStatus() == OrderStatus.CANCELLED)
                .count();
        assertThat(cancelledCount).isEqualTo(1);

        int stock = inventoryRepository.findByTicketTypeId(ticketTypeId)
                .orElseThrow().getAvailableStock();
        assertThat(stock).isEqualTo(INITIAL_STOCK);
    }
}