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
import com.chendev.ticketflow.order.event.OrderCancelledEvent;
import com.chendev.ticketflow.order.port.InventoryPort;
import com.chendev.ticketflow.order.port.InventoryPort.DeductionResult;
import com.chendev.ticketflow.order.repository.OrderRepository;
import com.chendev.ticketflow.order.statemachine.OrderEvent;
import com.chendev.ticketflow.order.statemachine.OrderStateMachine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository           orderRepository;
    private final TicketTypeRepository      ticketTypeRepository;
    private final EventRepository           eventRepository;
    private final InventoryPort             inventoryPort;
    private final OrderStateMachine         orderStateMachine;
    private final StringRedisTemplate       redisTemplate;
    private final ApplicationEventPublisher eventPublisher;

    @Lazy
    @Autowired
    private OrderService self;

    private static final String   IDEMPOTENCY_KEY_PREFIX = "idempotency:";
    private static final Duration PROCESSING_TTL         = Duration.ofSeconds(30);
    private static final Duration COMPLETED_TTL          = Duration.ofHours(24);
    private static final String   PROCESSING             = "PROCESSING";

    /**
     * Creates an order using the Transactionless Fast Path.
     *
     * Phase 1 (no DB connection): Redis idempotency check + Redis Lua stock deduction.
     * Phase 2 (short @Transactional): DB guard write + order insert.
     *
     * Rejected requests (stock insufficient) never acquire a DB connection.
     * Only the ~1% that pass the Redis gate touch the database.
     *
     * NOT @Transactional — the DB transaction is pushed as far down as possible,
     * opened only inside persistOrder() after Redis confirms stock availability.
     */
    public OrderResponse createOrder(Long userId, CreateOrderRequest request) {

        // ── Phase 0: Redis idempotency gate (no DB connection) ───────────────

        OrderResponse cached = checkRedisIdempotency(request.getRequestId());
        if (cached != null) {
            return cached;
        }

        // ── Phase 1 + 2: deduct → persist ────────────────────────────────────

        DeductionResult deductionResult = null;
        try {
            // Phase 1: stock deduction — pure Redis Lua, no DB connection.
            // Falls back to DB adapter (REQUIRES_NEW) if Redis is unavailable.
            deductionResult = inventoryPort.deductStock(
                    request.getTicketTypeId(), request.getQuantity());

            // Phase 2: DB persist — short @Transactional, single connection.
            // Validates ticket type, syncs deduction to DB, saves order.
            OrderResponse response = self.persistOrder(userId, request, deductionResult);
            markIdempotencyCompleted(request.getRequestId(), response.getOrderNo());
            return response;

        } catch (DataIntegrityViolationException e) {
            // DB unique constraint on request_id — another thread won the insert race.
            // Stock was deducted (Redis or DB fallback); must compensate.
            if (deductionResult != null) {
                inventoryPort.compensateDeduction(
                        request.getTicketTypeId(), request.getQuantity(), deductionResult);
            }
            releaseIdempotencyKey(request.getRequestId());
            log.info("[Order] Idempotency enforced by DB constraint: requestId={}",
                    request.getRequestId());
            throw BizException.of(ResultCode.IDEMPOTENT_REJECTION,
                    "Duplicate request detected — order already exists");

        } catch (BizException e) {
            // Stock was deducted but order creation failed.
            // Compensate unless deductStock itself threw (deductionResult would be null).
            if (deductionResult != null) {
                inventoryPort.compensateDeduction(
                        request.getTicketTypeId(), request.getQuantity(), deductionResult);
            }

            // Optimistic lock conflict in DB fallback — check if another thread
            // already created the order with the same requestId.
            if (e.getCode() == ResultCode.INVENTORY_LOCK_FAILED.getCode()) {
                releaseIdempotencyKey(request.getRequestId());
                return orderRepository.findByRequestId(request.getRequestId())
                        .map(order -> {
                            log.info("[Order] Idempotency resolved after lock conflict: " +
                                    "requestId={}", request.getRequestId());
                            return OrderResponse.from(order);
                        })
                        .orElseThrow(() -> e);
            }
            releaseIdempotencyKey(request.getRequestId());
            throw e;

        } catch (Exception e) {
            if (deductionResult != null) {
                log.error("[Order] Persist failed, compensating: ticketTypeId={}, qty={}",
                        request.getTicketTypeId(), request.getQuantity());
                inventoryPort.compensateDeduction(
                        request.getTicketTypeId(), request.getQuantity(), deductionResult);
            }
            releaseIdempotencyKey(request.getRequestId());
            throw e;
        }
    }

    /**
     * Validates the ticket type, syncs the stock deduction to DB, and saves the order.
     *
     * This is the ONLY method that acquires a DB connection during order creation.
     * It must be public for the Spring proxy to intercept @Transactional.
     *
     * noRollbackFor = BizException: if ticket validation or DB guard check fails,
     * the transaction does not roll back (there's nothing to roll back — no writes
     * happened yet). The caller's catch block handles compensation.
     */
    @Transactional(noRollbackFor = BizException.class)
    public OrderResponse persistOrder(Long userId, CreateOrderRequest request,
                                      DeductionResult deductionResult) {

        // DB-level idempotency: another thread may have inserted while we were
        // in Phase 1. Check before doing any work.
        if (orderRepository.existsByRequestId(request.getRequestId())) {
            // Stock was already deducted in Phase 1 for THIS request.
            // Compensate, then return the existing order.
            inventoryPort.compensateDeduction(
                    request.getTicketTypeId(), request.getQuantity(), deductionResult);
            log.info("[Order] Duplicate requestId detected via DB, stock compensated: {}",
                    request.getRequestId());
            return orderRepository.findByRequestId(request.getRequestId())
                    .map(OrderResponse::from)
                    .orElseThrow(() -> BizException.of(ResultCode.ORDER_CREATE_FAILED,
                            "Idempotency key exists but order not found"));
        }

        // Validate ticket type — LAZY fetch of Event works because we're in a Session.
        TicketType ticketType = ticketTypeRepository.findById(request.getTicketTypeId())
                .orElseThrow(() -> BizException.of(ResultCode.TICKET_TYPE_NOT_FOUND,
                        "Ticket type not found: " + request.getTicketTypeId()));

        if (!ticketType.getEvent().isOnSale()) {
            throw BizException.of(ResultCode.EVENT_NOT_AVAILABLE,
                    "Event is not currently on sale");
        }

        // Sync deduction to DB — no-op if DB fallback was used.
        // Throws BizException if DB guard check fails (Redis/DB drift).
        inventoryPort.persistDeduction(
                request.getTicketTypeId(), request.getQuantity(), deductionResult);

        // Save order — all within this single short transaction.
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
    }

    // ─── Lifecycle operations (unchanged) ────────────────────────────────────

    /**
     * User-initiated cancellation.
     *
     * Inventory is restored asynchronously via Kafka.
     * OrderCancelledEvent is published inside the transaction.
     * @TransactionalEventListener(AFTER_COMMIT) forwards it to Kafka
     * only after the DB commit succeeds — no ghost restores.
     */
    @Transactional
    public OrderResponse cancelOrder(String orderNo, Long userId) {
        Order order = findOrderByNo(orderNo);

        if (!order.getUserId().equals(userId)) {
            throw BizException.of(ResultCode.ORDER_CANCEL_NOT_ALLOWED,
                    "You are not authorized to cancel this order");
        }

        orderStateMachine.handleEvent(order, OrderEvent.CANCEL_BY_USER,
                "Cancelled by user");
        orderRepository.save(order);

        eventPublisher.publishEvent(OrderCancelledEvent.of(
                order.getOrderNo(),
                order.getTicketTypeId(),
                order.getQuantity(),
                "Cancelled by user"
        ));

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
                "Ticket confirmed");
        orderRepository.save(order);

        log.info("[Order] Payment confirmed: orderNo={}", orderNo);
        return OrderResponse.from(order);
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(String orderNo, Long userId) {
        Order order = findOrderByNo(orderNo);
        if (!order.getUserId().equals(userId)) {
            throw BizException.of(ResultCode.ORDER_NOT_FOUND,
                    "Order not found: " + orderNo);
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

    /**
     * System-initiated cancellation (timeout).
     *
     * Accepts orderNo rather than an Order entity to avoid detached-entity
     * LazyInitializationException. See full explanation in previous version.
     */
    @Transactional
    public void cancelOrderBySystem(String orderNo) {
        Order order = findOrderByNo(orderNo);
        orderStateMachine.handleEvent(order, OrderEvent.SYSTEM_TIMEOUT,
                "Cancelled by system timeout");
        orderRepository.save(order);

        eventPublisher.publishEvent(OrderCancelledEvent.of(
                order.getOrderNo(),
                order.getTicketTypeId(),
                order.getQuantity(),
                "Cancelled by system timeout"
        ));

        log.info("[Order] Cancelled by system timeout: orderNo={}", order.getOrderNo());
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    private OrderResponse checkRedisIdempotency(String requestId) {
        String key = IDEMPOTENCY_KEY_PREFIX + requestId;
        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(key, PROCESSING, PROCESSING_TTL);

            if (Boolean.TRUE.equals(acquired)) {
                return null;
            }

            String value = redisTemplate.opsForValue().get(key);

            if (PROCESSING.equals(value)) {
                log.info("[Order] Request in-flight, rejecting concurrent duplicate: " +
                        "requestId={}", requestId);
                throw BizException.of(ResultCode.IDEMPOTENT_REJECTION,
                        "Request is being processed — please try again shortly");
            }

            if (value != null) {
                log.info("[Order] Idempotency cache hit: requestId={}, orderNo={}",
                        requestId, value);
                return orderRepository.findByOrderNo(value)
                        .map(OrderResponse::from)
                        .orElse(null);
            }

            return null;

        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[Order] Redis unavailable for idempotency check, " +
                            "falling back to DB: requestId={}, reason={}",
                    requestId, e.getMessage());
            return null;
        }
    }

    private void markIdempotencyCompleted(String requestId, String orderNo) {
        try {
            redisTemplate.opsForValue()
                    .set(IDEMPOTENCY_KEY_PREFIX + requestId, orderNo, COMPLETED_TTL);
        } catch (Exception e) {
            log.warn("[Order] Failed to update idempotency key: requestId={}, orderNo={}",
                    requestId, orderNo);
        }
    }

    private void releaseIdempotencyKey(String requestId) {
        try {
            redisTemplate.delete(IDEMPOTENCY_KEY_PREFIX + requestId);
        } catch (Exception e) {
            log.warn("[Order] Failed to release idempotency key: requestId={}", requestId);
        }
    }

    private Order findOrderByNo(String orderNo) {
        return orderRepository.findByOrderNo(orderNo)
                .orElseThrow(() -> BizException.of(ResultCode.ORDER_NOT_FOUND,
                        "Order not found: " + orderNo));
    }

    private String generateOrderNo() {
        return "TF" + Long.toHexString(System.currentTimeMillis()).toUpperCase()
                + UUID.randomUUID().toString().replace("-", "").substring(0, 5).toUpperCase();
    }
}
