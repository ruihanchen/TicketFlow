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

// Two-tier expiry: cart deadline (expiredAt) guards payOrder(); payment deadline (paymentExpiredAt)
// guards confirmPayment(). The two fields are independent, the V4 bug used expiredAt for both,
// rejecting in-flight payments where the gateway confirmation arrived slightly after the cart deadline.
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
        // Deletion order respects FK constraints: orders -> ticket_types -> events
        orderRepository.deleteAll();
        inventoryRepository.deleteAll();
        ticketTypeRepository.deleteAll();
        eventRepository.deleteAll();

        Instant now = Instant.now();
        Event event = Event.create("Expiry Test Event", "desc", "venue",
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
        assertThat(reloaded.getPaymentExpiredAt()).isNull();
    }

    @Test
    void cart_expired_order_rejects_payment_without_deducting_stock() {
        String orderNo = createAndExpireCart();
        int stockAfterCreate = availableStock();

        assertThatThrownBy(() -> orderService.payOrder(USER_ID, orderNo))
                .isInstanceOf(DomainException.class);

        // payOrder() rejection is a no-op (only cancellation restores stock)
        assertThat(availableStock()).isEqualTo(stockAfterCreate);
    }

    @Test
    void payment_expired_order_cannot_confirm() {
        String orderNo = createOrder();
        orderService.payOrder(USER_ID, orderNo);

        Order order = orderRepository.findByOrderNo(orderNo).orElseThrow();
        order.expirePaymentNow();
        orderRepository.save(order);

        assertThatThrownBy(() -> orderService.confirmPayment(USER_ID, orderNo))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getResultCode())
                .isEqualTo(ResultCode.ORDER_EXPIRED);

        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAYING);
    }

    @Test
    void payment_expired_rejection_does_not_restore_stock() {
        // Confirm failure leaves the order PAYING; stock stays held until explicit cancellation.
        String orderNo = createOrder();
        orderService.payOrder(USER_ID, orderNo);
        orderRepository.findByOrderNo(orderNo).ifPresent(o -> {
            o.expirePaymentNow();
            orderRepository.save(o);
        });

        int stockBefore = availableStock();
        assertThatThrownBy(() -> orderService.confirmPayment(USER_ID, orderNo))
                .isInstanceOf(DomainException.class);

        assertThat(availableStock()).isEqualTo(stockBefore);
    }

    @Test
    void in_flight_payment_succeeds_even_when_cart_deadline_passed() {
        // Core V4 invariant: confirmPayment() checks paymentExpiredAt, not expiredAt.
        // User clicks Pay just before cart deadline; gateway round-trip pushes confirm past expiredAt.
        String orderNo = createOrder();
        orderService.payOrder(USER_ID, orderNo);

        // Cart deadline passes; paymentExpiredAt remains in the future
        Order order = orderRepository.findByOrderNo(orderNo).orElseThrow();
        order.expireNow();
        orderRepository.save(order);

        orderService.confirmPayment(USER_ID, orderNo);

        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAID);
    }

    @Test
    void happy_path_unexpired_order_reaches_paid() {
        String orderNo = createOrder();
        orderService.payOrder(USER_ID, orderNo);
        orderService.confirmPayment(USER_ID, orderNo);

        assertThat(orderRepository.findByOrderNo(orderNo).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.PAID);
        assertThat(availableStock()).isEqualTo(INITIAL_STOCK - 1);
    }

    private String createOrder() {
        return orderService.createOrder(USER_ID,
                        CreateOrderRequest.forTest(ticketTypeId, 1, UUID.randomUUID().toString()))
                .getOrderNo();
    }

    private String createAndExpireCart() {
        String orderNo = createOrder();
        Order order = orderRepository.findByOrderNo(orderNo).orElseThrow();
        order.expireNow();
        orderRepository.save(order);
        return orderNo;
    }

    private int availableStock() {
        return inventoryRepository.findByTicketTypeId(ticketTypeId).orElseThrow().getAvailableStock();
    }
}