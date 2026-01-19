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

// Two-tier expiry: cart deadline guards payOrder; payment deadline guards confirmPayment.
// Splits the old single-deadline model that rejected in-flight payments confirmed past expiredAt.
class ExpiredOrderRejectionTest extends IntegrationTestBase {

    @Autowired private OrderService         orderService;
    @Autowired private OrderRepository      orderRepository;
    @Autowired private InventoryRepository  inventoryRepository;
    @Autowired private EventRepository      eventRepository;
    @Autowired private TicketTypeRepository ticketTypeRepository;
    @Autowired private InventoryService     inventoryService;

    private static final int        INITIAL_STOCK = 5;
    private static final Long       USER_ID       = 1L;
    private static final BigDecimal TICKET_PRICE  = new BigDecimal("100.00");

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

        TicketType tt = TicketType.create(event, "General", TICKET_PRICE, INITIAL_STOCK);
        ticketTypeRepository.save(tt);
        ticketTypeId = tt.getId();

        inventoryService.initStock(ticketTypeId, INITIAL_STOCK);
    }

    @Test
    void cart_expired_order_cannot_initiate_payment() {
        String orderNo = createAndExpireCart();

        assertThatThrownBy(() -> orderService.payOrder(USER_ID, orderNo))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getResultCode())
                .isEqualTo(ResultCode.ORDER_EXPIRED);

        Order reloaded = orderRepository.findByOrderNo(orderNo).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    void payment_expired_order_cannot_confirm_payment() {
        String orderNo = createOrder();
        orderService.payOrder(USER_ID, orderNo);
        Order order = orderRepository.findByOrderNo(orderNo).orElseThrow();
        order.expirePaymentNow();
        orderRepository.save(order);

        assertThatThrownBy(() -> orderService.confirmPayment(USER_ID, orderNo))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getResultCode())
                .isEqualTo(ResultCode.ORDER_EXPIRED);

        Order reloaded = orderRepository.findByOrderNo(orderNo).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(OrderStatus.PAYING);
    }

    @Test
    void in_flight_payment_succeeds_even_when_cart_deadline_passed() {
        // cart deadline passed during gateway round-trip; payment must still go through
        String orderNo = createOrder();
        orderService.payOrder(USER_ID, orderNo);
        Order order = orderRepository.findByOrderNo(orderNo).orElseThrow();
        order.expireNow();  // expires cart; paymentExpiredAt remains in the future
        orderRepository.save(order);
        orderService.confirmPayment(USER_ID, orderNo);

        Order reloaded = orderRepository.findByOrderNo(orderNo).orElseThrow();
        assertThat(reloaded.getStatus())
                .as("in-flight payment within payment window must finalize regardless of cart deadline")
                .isEqualTo(OrderStatus.PAID);
    }

    @Test
    void unexpired_order_can_pay_normally() {
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

    private String createAndExpireCart() {
        String orderNo = createOrder();
        Order order = orderRepository.findByOrderNo(orderNo).orElseThrow();
        order.expireNow();
        orderRepository.save(order);
        return orderNo;
    }
}