package com.chendev.ticketflow.order.service;

import com.chendev.ticketflow.common.exception.BizException;
import com.chendev.ticketflow.common.response.PageResult;
import com.chendev.ticketflow.common.response.ResultCode;
import com.chendev.ticketflow.event.entity.TicketType;
import com.chendev.ticketflow.event.repository.EventRepository;
import com.chendev.ticketflow.event.repository.TicketTypeRepository;
import com.chendev.ticketflow.order.dto.CreateOrderRequest;
import com.chendev.ticketflow.order.dto.OrderResponse;
import com.chendev.ticketflow.order.entity.Order;
import com.chendev.ticketflow.order.port.InventoryPort;
import com.chendev.ticketflow.order.repository.OrderRepository;
import com.chendev.ticketflow.order.statemachine.OrderEvent;
import com.chendev.ticketflow.order.statemachine.OrderStateMachine;
import com.chendev.ticketflow.order.statemachine.OrderStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final TicketTypeRepository ticketTypeRepository;
    private final EventRepository eventRepository;
    private final InventoryPort inventoryPort;
    private final OrderStateMachine orderStateMachine;

    @Transactional
    public OrderResponse createOrder(Long userId, CreateOrderRequest request) {

        // ── Step 1: Idempotency check ─────────────────────────────────────────
        // Same requestId = duplicate request, return existing order immediately
        if (orderRepository.existsByRequestId(request.getRequestId())) {
            log.info("[Order] Duplicate requestId detected: {}", request.getRequestId());
            return orderRepository.findByRequestId(request.getRequestId())
                    .map(OrderResponse::from)
                    .orElseThrow(() -> BizException.of(ResultCode.ORDER_NOT_FOUND));
        }

        // ── Step 2: Validate ticket type and event ────────────────────────────
        TicketType ticketType = ticketTypeRepository.findById(request.getTicketTypeId())
                .orElseThrow(() -> BizException.of(ResultCode.TICKET_TYPE_NOT_FOUND));

        // Validate the event is currently on sale
        var event = eventRepository.findById(ticketType.getEvent().getId())
                .orElseThrow(() -> BizException.of(ResultCode.EVENT_NOT_FOUND));

        if (!event.isOnSale()) {
            throw BizException.of(ResultCode.EVENT_NOT_AVAILABLE,
                    "Event '" + event.getName() + "' is not currently on sale");
        }

        // ── Step 3: Check stock before attempting deduction ───────────────────
        if (!inventoryPort.hasSufficientStock(
                request.getTicketTypeId(), request.getQuantity())) {
            throw BizException.of(ResultCode.INVENTORY_INSUFFICIENT);
        }

        // ── Step 4: Deduct inventory ──────────────────────────────────────────
        // This may throw BizException if optimistic lock conflict occurs
        inventoryPort.deductStock(request.getTicketTypeId(), request.getQuantity());

        // ── Step 5: Create order ──────────────────────────────────────────────
        try {
            String orderNo = generateOrderNo();
            Order order = Order.create(
                    orderNo,
                    userId,
                    request.getTicketTypeId(),
                    request.getQuantity(),
                    ticketType.getPrice(),
                    request.getRequestId()
            );
            orderRepository.save(order);

            log.info("[Order] Created: orderNo={}, userId={}, ticketTypeId={}, qty={}",
                    orderNo, userId, request.getTicketTypeId(), request.getQuantity());

            return OrderResponse.from(order);

        } catch (Exception e) {
            // ── Step 6: Compensate — release inventory if order creation fails ─
            // This is our manual SAGA compensation before Phase 2 messaging
            log.error("[Order] Order creation failed, releasing inventory: " +
                    "ticketTypeId={}, qty={}", request.getTicketTypeId(), request.getQuantity());
            inventoryPort.releaseStock(
                    request.getTicketTypeId(), request.getQuantity());
            throw e;
        }
    }

    @Transactional
    public OrderResponse cancelOrder(String orderNo, Long userId) {
        Order order = findOrderByNo(orderNo);

        // Ownership check — users can only cancel their own orders
        if (!order.getUserId().equals(userId)) {
            throw BizException.of(ResultCode.ORDER_CANCEL_NOT_ALLOWED,
                    "You are not authorized to cancel this order");
        }

        if (!order.isCancellable()) {
            throw BizException.of(ResultCode.ORDER_CANCEL_NOT_ALLOWED,
                    "Order in status [" + order.getStatus() + "] cannot be cancelled");
        }

        // Drive state transition through state machine
        orderStateMachine.handleEvent(order, OrderEvent.CANCEL_BY_USER,
                "Cancelled by user");

        // Release inventory — compensation for the original deduction
        inventoryPort.releaseStock(order.getTicketTypeId(), order.getQuantity());

        orderRepository.save(order);

        log.info("[Order] Cancelled by user: orderNo={}, userId={}", orderNo, userId);
        return OrderResponse.from(order);
    }

    @Transactional
    public OrderResponse initiatePayment(String orderNo, Long userId) {
        Order order = findOrderByNo(orderNo);

        if (!order.getUserId().equals(userId)) {
            throw BizException.of(ResultCode.ORDER_STATUS_INVALID,
                    "You are not authorized to pay this order");
        }

        if (order.isExpired()) {
            throw BizException.of(ResultCode.ORDER_EXPIRED,
                    "Order has expired, please create a new order");
        }

        orderStateMachine.handleEvent(order, OrderEvent.INITIATE_PAYMENT,
                "Payment initiated by user");
        orderRepository.save(order);

        log.info("[Order] Payment initiated: orderNo={}", orderNo);
        return OrderResponse.from(order);
    }

    @Transactional
    public OrderResponse confirmPayment(String orderNo) {
        Order order = findOrderByNo(orderNo);

        orderStateMachine.handleEvent(order, OrderEvent.PAYMENT_SUCCESS,
                "Payment confirmed");
        orderStateMachine.handleEvent(order, OrderEvent.CONFIRM_TICKET,
                "Ticket confirmed after payment");
        orderRepository.save(order);

        log.info("[Order] Payment confirmed and ticket issued: orderNo={}", orderNo);
        return OrderResponse.from(order);
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(String orderNo, Long userId) {
        Order order = findOrderByNo(orderNo);

        if (!order.getUserId().equals(userId)) {
            throw BizException.of(ResultCode.ORDER_NOT_FOUND,
                    "Order not found");
        }
        return OrderResponse.from(order);
    }

    @Transactional(readOnly = true)
    public PageResult<OrderResponse> getUserOrders(Long userId, Pageable pageable) {
        return PageResult.of(
                orderRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable)
                        .map(OrderResponse::from)
        );
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private Order findOrderByNo(String orderNo) {
        return orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> BizException.of(ResultCode.ORDER_NOT_FOUND,
                        "Order #" + orderNo + " not found"));
    }

    // OrderNo format: TF + timestamp millis + random 6-char suffix
    // Readable, unique, non-sequential — doesn't leak business volume
    private String generateOrderNo() {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 6)
                .toUpperCase();
        return "TF" + timestamp + suffix;
    }
}
