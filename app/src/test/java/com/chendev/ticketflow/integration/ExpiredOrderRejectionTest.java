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
import com.chendev.ticketflow.order.dto.CreateOrderRequest;
import com.chendev.ticketflow.order.entity.Order;
import com.chendev.ticketflow.order.repository.OrderRepository;
import com.chendev.ticketflow.order.service.OrderService;
import com.chendev.ticketflow.order.statemachine.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Guards against paying an expired order. Without this check, OrderTimeoutService could race the user's payment on
// the next polling cycle,releasing inventory while the user believes payment is in progress.
class ExpiredOrderRejectionTest extends IntegrationTestBase {

    @Autowired private OrderService         orderService;
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
        orderRepository.deleteAll();
        inventoryRepository.deleteAll();
        ticketTypeRepository.deleteAll();
        eventRepository.deleteAll();

        Instant now = Instant.now();
        Event event = Event.create(
                "Expiry Test Event", "desc", "venue",
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
    void expired_order_cannot_initiate_payment() {
        String orderNo = createAndExpireOrder();

        assertThatThrownBy(() -> orderService.payOrder(USER_ID, orderNo))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getResultCode())
                .isEqualTo(ResultCode.ORDER_EXPIRED);

        // status must stay CREATED, if the guard threw via transition instead, this assertion would
        // silently pass on the wrong invariant
        Order reloaded = orderRepository.findByOrderNo(orderNo).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    void expired_order_cannot_confirm_payment() {
        // payment was initiated before expiry but confirmation arrived after payment gateway latency
        // pushed past the window
        String orderNo = createOrder();
        orderService.payOrder(USER_ID, orderNo);

        Order order = orderRepository.findByOrderNo(orderNo).orElseThrow();
        order.expireNow();
        orderRepository.save(order);

        assertThatThrownBy(() -> orderService.confirmPayment(USER_ID, orderNo))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getResultCode())
                .isEqualTo(ResultCode.ORDER_EXPIRED);

        Order reloaded = orderRepository.findByOrderNo(orderNo).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.PAYING);
    }

    @Test
    void unexpired_order_can_pay_normally() {
        // guard must not reject valid requests
        String orderNo = createOrder();

        orderService.payOrder(USER_ID, orderNo);
        orderService.confirmPayment(USER_ID, orderNo);

        Order reloaded = orderRepository.findByOrderNo(orderNo).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    private String createOrder() {
        var response = orderService.createOrder(USER_ID,
                CreateOrderRequest.forTest(ticketTypeId, 1, UUID.randomUUID().toString()));
        return response.getOrderNo();
    }

    private String createAndExpireOrder() {
        String orderNo = createOrder();
        Order order = orderRepository.findByOrderNo(orderNo).orElseThrow();
        order.expireNow();
        orderRepository.save(order);
        return orderNo;
    }
}