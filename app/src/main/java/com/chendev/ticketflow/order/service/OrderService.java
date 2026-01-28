package com.chendev.ticketflow.order.service;

import com.chendev.ticketflow.common.exception.DomainException;
import com.chendev.ticketflow.common.response.ResultCode;
import com.chendev.ticketflow.order.dto.CreateOrderRequest;
import com.chendev.ticketflow.order.dto.OrderResponse;
import com.chendev.ticketflow.order.entity.Order;
import com.chendev.ticketflow.order.factory.OrderNoFactory;
import com.chendev.ticketflow.order.metrics.OrderMetrics;
import com.chendev.ticketflow.inventory.port.DeductionResult;
import com.chendev.ticketflow.order.port.EventPort;
import com.chendev.ticketflow.order.port.InventoryPort;
import com.chendev.ticketflow.order.port.TicketTypeInfo;
import com.chendev.ticketflow.order.repository.OrderRepository;
import com.chendev.ticketflow.order.statemachine.OrderEvent;
import com.chendev.ticketflow.order.statemachine.OrderStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final InventoryPort   inventoryPort;
    private final EventPort       eventPort;
    private final OrderNoFactory  orderNoFactory;
    private final OrderMetrics    orderMetrics;

    //cart hold window: how long a CREATED order survives before the reaper releases it
    @Value("${ticketflow.order.cart-window-minutes:15}")
    private int cartWindowMinutes;

    //payment completion window: starts when user clicks pay, sized for gateway round-trip + 3DS
    @Value("${ticketflow.order.payment-window-minutes:5}")
    private int paymentWindowMinutes;

    @Transactional
    public OrderResponse createOrder(Long userId, CreateOrderRequest req) {
        //fast path: catch sequential duplicates without hitting a constraint.
        if (orderRepository.existsByRequestId(req.getRequestId())) {
            log.info("[Order] duplicate request: requestId={}", req.getRequestId());
            orderMetrics.recordDuplicate();
            return new OrderResponse(
                    orderRepository.findByRequestId(req.getRequestId())
                            .orElseThrow(() -> DomainException.of(ResultCode.ORDER_NOT_FOUND)));
        }

        TicketTypeInfo ticketTypeInfo = eventPort.getTicketTypeInfo(req.getTicketTypeId());

        if (!ticketTypeInfo.onSale()) {
            throw DomainException.of(ResultCode.EVENT_NOT_ON_SALE);
        }

        DeductionResult result = inventoryPort.deductStock(req.getTicketTypeId(), req.getQuantity());
        if (result == DeductionResult.INSUFFICIENT) {
            throw DomainException.of(ResultCode.INSUFFICIENT_STOCK);
        }

        String orderNo = orderNoFactory.create();

        Order order = Order.create(
                orderNo, userId, req.getTicketTypeId(), req.getQuantity(),
                ticketTypeInfo.price(), req.getRequestId(),
                Duration.ofMinutes(cartWindowMinutes));

        try {
            orderRepository.save(order);
            orderRepository.flush();
        } catch (DataIntegrityViolationException e) {
            // UUIDv7 makes orderNo collisions impossible; only requestId can reach here (TOCTOU).
            // TX rollback undoes the deduction. Counted separately from the fast-path duplicate check.
            orderMetrics.recordDuplicate();
            throw DomainException.of(ResultCode.DUPLICATE_REQUEST,
                    "duplicate requestId after concurrent insert", e);
        }

        log.info("[Order] created: orderNo={}, userId={}, ticketTypeId={}",
                order.getOrderNo(), userId, req.getTicketTypeId());
        orderMetrics.recordCreated();

        return new OrderResponse(order);
    }

    @Transactional
    public OrderResponse payOrder(Long userId, String orderNo) {
        Order order = findByOrderNoForUser(orderNo, userId);
        //cart deadline check, unauthorized callers can't probe order state via expiry errors
        rejectIfCartExpired(order);
        order.transitionTo(OrderStatus.PAYING, OrderEvent.INITIATE_PAYMENT,
                "payment initiated");
        //starts the payment deadline; cart deadline is irrelevant for this order from here on
        order.startPaymentWindow(Duration.ofMinutes(paymentWindowMinutes));
        orderRepository.save(order);
        log.info("[Order] paying: orderNo={}, paymentExpiredAt={}",
                orderNo, order.getPaymentExpiredAt());
        return new OrderResponse(order);
    }

    @Transactional
    public OrderResponse confirmPayment(Long userId, String orderNo) {
        Order order = findByOrderNoForUser(orderNo, userId);
        //payment deadline check, not cart deadline, the user is entitled to their full payment window
        //even if the cart deadline elapsed during the gateway round-trip
        rejectIfPaymentExpired(order);
        order.transitionTo(OrderStatus.PAID, OrderEvent.PAYMENT_SUCCESS,
                "payment confirmed");
        orderRepository.save(order);
        log.info("[Order] paid: orderNo={}", orderNo);
        return new OrderResponse(order);
    }

    @Transactional
    public OrderResponse cancelOrder(Long userId, String orderNo) {
        Order order = findByOrderNoForUser(orderNo, userId);
        executeCancel(order, OrderEvent.CANCEL_BY_USER, "cancelled by user");
        return new OrderResponse(order);
    }

    // returns true if an order was processed, false when the backlog is empty
    @Transactional
    public boolean processOneExpiredOrder() {
        //SKIP LOCKED: concurrent reapers get disjoint rows, no external lock needed query is hardcoded
        //to CREATED only, PAYING orders are never the reaper's concern
        List<Order> locked = orderRepository.findExpiredCreatedForUpdate(
                Instant.now(), PageRequest.of(0, 1));

        if (locked.isEmpty()) {
            return false;
        }

        Order order = locked.get(0);
        order.transitionTo(OrderStatus.CANCELLED, OrderEvent.SYSTEM_TIMEOUT,
                "payment window expired");
        //transitionTo() validates state machine and writes statusHistory even though status is guaranteed CREATED
        inventoryPort.releaseStock(order.getTicketTypeId(), order.getQuantity());
        //order is managed; no explicit save needed(@Version increments at flush)
        log.info("[Order] cancelled by system: orderNo={}", order.getOrderNo());
        return true;
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(Long userId, String orderNo) {
        return new OrderResponse(findByOrderNoForUser(orderNo, userId));
    }

    @Transactional(readOnly = true)
    public Page<OrderResponse> getUserOrders(Long userId, Pageable pageable) {
        return orderRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable)
                .map(OrderResponse::new);
    }

    private void executeCancel(Order order, OrderEvent event, String reason) {
        order.transitionTo(OrderStatus.CANCELLED, event, reason);
        inventoryPort.releaseStock(order.getTicketTypeId(), order.getQuantity());
        orderRepository.save(order);
        log.info("[Order] cancelled: orderNo={}, reason={}", order.getOrderNo(), reason);
    }

    //ORDER_NOT_FOUND instead of FORBIDDEN:don't let users enumerate others' orders
    private Order findByOrderNoForUser(String orderNo, Long userId) {
        return orderRepository.findByOrderNoAndUserId(orderNo, userId)
                .orElseThrow(() -> DomainException.of(ResultCode.ORDER_NOT_FOUND));
    }

    //cart-deadline guard: payOrder() only; confirmPayment() uses rejectIfPaymentExpired()
    private void rejectIfCartExpired(Order order) {
        if (order.isCartExpired()) {
            throw DomainException.of(ResultCode.ORDER_EXPIRED);
        }
    }

    //payment-deadline guard: NULL paymentExpiredAt means no attempt started; safe to call on any state
    private void rejectIfPaymentExpired(Order order) {
        if (order.isPaymentExpired()) {
            throw DomainException.of(ResultCode.ORDER_EXPIRED);
        }
    }
}