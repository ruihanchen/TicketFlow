package com.chendev.ticketflow.order.service;

import com.chendev.ticketflow.common.exception.BizException;
import com.chendev.ticketflow.common.exception.SystemException;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    // noRollbackFor = BizException.class:
    // BizException represents a handled business condition (lock conflict, duplicate
    // request). Without this, Spring marks the transaction rollback-only when
    // BizException escapes from InventoryAdapter.deductStock(), preventing our
    // catch blocks from executing idempotency recovery logic.
    @Transactional(noRollbackFor = BizException.class)
    public OrderResponse createOrder(Long userId, CreateOrderRequest request) {
        try {
            return doCreateOrder(userId, request);
        } catch (DataIntegrityViolationException e) {
            // The DB UNIQUE constraint on request_id caught a concurrent duplicate.
            // The Hibernate session is now corrupted — do NOT query it.
            // Signal idempotency rejection cleanly so the caller can handle it.
            log.info("[Order] Idempotency enforced by DB constraint: requestId={}",
                    request.getRequestId());
            throw BizException.of(ResultCode.IDEMPOTENT_REJECTION,
                    "Duplicate request detected — order already exists");
        } catch (BizException e) {
            if (e.getCode() == ResultCode.INVENTORY_LOCK_FAILED.getCode()) {
                // Concurrent requests with the same requestId all passed the idempotency
                // check, then raced at inventory deduction. The winner created the order;
                // these are the losers. Check if the order now exists — if so, return it.
                return orderRepository.findByRequestId(request.getRequestId())
                        .map(order -> {
                            log.info("[Order] Idempotency resolved after lock conflict: " +
                                    "requestId={}", request.getRequestId());
                            return OrderResponse.from(order);
                        })
                        .orElseThrow(() -> e); // Genuine lock conflict, not idempotency
            }
            throw e;
        }
    }

    private OrderResponse doCreateOrder(Long userId, CreateOrderRequest request) {

        // ── Step 1: Idempotency check (fast path) ────────────────────────────
        // Handles the sequential retry case: if a previous request already committed,
        // return that order immediately. The DB UNIQUE constraint + noRollbackFor
        // handle the concurrent case in the outer method.
        if (orderRepository.existsByRequestId(request.getRequestId())) {
            log.info("[Order] Duplicate requestId detected: {}", request.getRequestId());
            return orderRepository.findByRequestId(request.getRequestId())
                    .map(OrderResponse::from)
                    .orElseThrow(() -> BizException.of(ResultCode.ORDER_CREATE_FAILED,
                            "Idempotency key exists but order not found"));
        }

        // ── Step 2: Validate ticket type and event ────────────────────────────
        TicketType ticketType = ticketTypeRepository.findById(request.getTicketTypeId())
                .orElseThrow(() -> BizException.of(ResultCode.TICKET_TYPE_NOT_FOUND,
                        "Ticket type not found: " + request.getTicketTypeId()));

        if (!ticketType.getEvent().isOnSale()) {
            throw BizException.of(ResultCode.EVENT_NOT_AVAILABLE,
                    "Event is not currently on sale");
        }

        // ── Step 3: Deduct inventory ──────────────────────────────────────────
        // deductStock() is the single point of failure for inventory.
        // No pre-check (hasSufficientStock) here — a separate check would introduce
        // a TOCTOU race and create two different error paths for the same root cause.
        inventoryPort.deductStock(request.getTicketTypeId(), request.getQuantity());

        // ── Step 4: Create order ──────────────────────────────────────────────
        try {
            Order order = Order.create(
                    generateOrderNo(),
                    userId,
                    request.getTicketTypeId(),
                    request.getQuantity(),
                    ticketType.getPrice(),
                    request.getRequestId()
            );

            orderRepository.save(order);

            log.info("[Order] Created: orderNo={}, userId={}, ticketTypeId={}, qty={}",
                    order.getOrderNo(), userId, request.getTicketTypeId(),
                    request.getQuantity());

            return OrderResponse.from(order);

        } catch (Exception e) {
            if (e instanceof DataIntegrityViolationException) {
                // Idempotency race: deductStock() succeeded, but save(order) hit the
                // UNIQUE constraint because another thread committed first.
                //
                // Do NOT call releaseStock() here for two reasons:
                // 1. The Hibernate session is corrupted after a constraint violation —
                //    any further queries will throw AssertionFailure.
                // 2. deductStock() already committed via REQUIRES_NEW. The real reason
                //    we skip compensation here is Hibernate session corruption — any
                //    further queries on this session will throw AssertionFailure.
                //    This creates a known inventory leak risk for idempotency races.
                //    Phase 2 Redis idempotency (SETNX) eliminates this race entirely,
                //    making this code path unreachable in practice.
                //
                // Phase 2 warning: when deductStock() becomes a Redis call outside
                // this transaction, compensation WILL be needed here, but it must
                // run in a fresh session/transaction, not this corrupted one.
                throw e;
            }

            // For all other failures: compensate the inventory deduction.
            // deductStock() runs in REQUIRES_NEW — its transaction committed independently
            // before we reach here. releaseStock() is load-bearing in Phase 1, not just
            // a Phase 2 preparation. If releaseStock() also fails, we have a data
            // inconsistency that requires reconciliation.
            log.error("[Order] Order creation failed, releasing inventory: ticketTypeId={}, qty={}",
                    request.getTicketTypeId(), request.getQuantity());
            try {
                inventoryPort.releaseStock(request.getTicketTypeId(), request.getQuantity());
            } catch (Exception compensationException) {
                log.error("[CRITICAL] Inventory compensation failed. Manual reconciliation required. " +
                                "ticketTypeId={}, quantity={}, originalError='{}', compensationError='{}'",
                        request.getTicketTypeId(), request.getQuantity(),
                        e.getMessage(), compensationException.getMessage());
                // Intentionally not rethrowing — original exception has higher diagnostic value.
            }
            throw e;
        }
    }

    @Transactional
    public OrderResponse cancelOrder(String orderNo, Long userId) {
        Order order = findOrderByNo(orderNo);

        if (!order.getUserId().equals(userId)) {
            throw BizException.of(ResultCode.ORDER_CANCEL_NOT_ALLOWED,
                    "You are not authorized to cancel this order");
        }

        // The state machine is the single source of truth for valid transitions.
        // If CANCEL_BY_USER is not defined for the current status, handleEvent()
        // throws ORDER_STATUS_INVALID. No pre-check needed — having both isCancellable()
        // and the state machine creates two sources of truth that can silently diverge.
        orderStateMachine.handleEvent(order, OrderEvent.CANCEL_BY_USER,
                "Cancelled by user");

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
            throw BizException.of(ResultCode.ORDER_NOT_FOUND, "Order not found");
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

    @Transactional
    public void cancelOrderBySystem(String orderNo) {
        Order order = findOrderByNo(orderNo);
        orderStateMachine.handleEvent(order, OrderEvent.SYSTEM_TIMEOUT,
                "Order expired after 15 minutes");
        inventoryPort.releaseStock(order.getTicketTypeId(), order.getQuantity());
        orderRepository.save(order);
        log.info("[Order] Cancelled by system timeout: orderNo={}", orderNo);
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
