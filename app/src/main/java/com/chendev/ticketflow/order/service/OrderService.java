package com.chendev.ticketflow.order.service;

import com.chendev.ticketflow.common.exception.DomainException;
import com.chendev.ticketflow.common.response.ResultCode;
import com.chendev.ticketflow.order.dto.CreateOrderRequest;
import com.chendev.ticketflow.order.dto.OrderResponse;
import com.chendev.ticketflow.order.entity.Order;
import com.chendev.ticketflow.order.port.EventPort;
import com.chendev.ticketflow.order.port.InventoryPort;
import com.chendev.ticketflow.order.port.InventoryPort.DeductionResult;
import com.chendev.ticketflow.order.port.TicketTypeInfo;
import com.chendev.ticketflow.order.repository.OrderRepository;
import com.chendev.ticketflow.order.statemachine.OrderEvent;
import com.chendev.ticketflow.order.statemachine.OrderStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final InventoryPort   inventoryPort;
    private final EventPort       eventPort;

    @Value("${ticketflow.order.payment-window-minutes:15}")
    private int paymentWindowMinutes;

    @Transactional
    public OrderResponse createOrder(Long userId, CreateOrderRequest req) {
        //fast path: catch sequential duplicates without hitting a constraint.
        if (orderRepository.existsByRequestId(req.getRequestId())) {
            log.info("[Order] duplicate request: requestId={}", req.getRequestId());
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
        if (result == DeductionResult.LOCK_CONFLICT) {
            // @Version conflict path;only reachable via ConcurrentInventoryTest (direct InventoryService call).
            // InventoryAdapter uses conditional UPDATE and never returns LOCK_CONFLICT.
            throw DomainException.of(ResultCode.INVENTORY_LOCK_FAILED);
        }

        String orderNo = "TF" + System.currentTimeMillis()
                + UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        Order order = Order.create(
                orderNo, userId, req.getTicketTypeId(), req.getQuantity(),
                ticketTypeInfo.price(), req.getRequestId(),
                Duration.ofMinutes(paymentWindowMinutes));

        try {
            orderRepository.save(order);
            orderRepository.flush();
        } catch (DataIntegrityViolationException e) {
            //concurrent duplicate requestId: session is rollback-only after this,
            //DB deduction rolls back with the TX; Redis needs explicit compensation.
            safeReleaseStock(req.getTicketTypeId(), req.getQuantity());
            throw DomainException.of(ResultCode.DUPLICATE_REQUEST,
                    "order already exists for this requestId", e);
        } catch (Exception e) {
            //any other DB failure:same compensation logic
            safeReleaseStock(req.getTicketTypeId(), req.getQuantity());
            throw e;
        }

        log.info("[Order] created: orderNo={}, userId={}, ticketTypeId={}",
                order.getOrderNo(), userId, req.getTicketTypeId());

        return new OrderResponse(order);
    }

    @Transactional
    public OrderResponse payOrder(Long userId, String orderNo) {
        Order order = findByOrderNoForUser(orderNo, userId);
        order.transitionTo(OrderStatus.PAYING, OrderEvent.INITIATE_PAYMENT,
                "payment initiated");
        orderRepository.save(order);
        log.info("[Order] paying: orderNo={}", orderNo);
        return new OrderResponse(order);
    }

    @Transactional
    public OrderResponse confirmPayment(Long userId, String orderNo) {
        Order order = findByOrderNoForUser(orderNo, userId);
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

    //system timeout: no ownership check; called by OrderTimeoutService.
    @Transactional
    public void cancelOrderBySystem(String orderNo) {
        Order order = findByOrderNo(orderNo);
        executeCancel(order, OrderEvent.SYSTEM_TIMEOUT, "payment window expired");
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

    //DB stock is restored by TX rollback; Redis needs explicit INCRBY.
    //DB release will fail here (TX is rollback-only),that's expected,
    //Redis INCRBY already completed (Redis-first ordering in InventoryAdapter).
    private void safeReleaseStock(Long ticketTypeId, int quantity) {
        try {
            inventoryPort.releaseStock(ticketTypeId, quantity);
        } catch (Exception e) {
            log.warn("[Order] DB release failed during compensation (expected if TX rolled back), " +
                    "Redis already compensated: ticketTypeId={}, qty={}", ticketTypeId, quantity);
        }
    }

    //ORDER_NOT_FOUND instead of FORBIDDEN:don't let users enumerate others' orders
    private Order findByOrderNoForUser(String orderNo, Long userId) {
        return orderRepository.findByOrderNoAndUserId(orderNo, userId)
                .orElseThrow(() -> DomainException.of(ResultCode.ORDER_NOT_FOUND));
    }

    //no ownership check: system processes only.
    private Order findByOrderNo(String orderNo) {
        return orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> DomainException.of(ResultCode.ORDER_NOT_FOUND));
    }
}
