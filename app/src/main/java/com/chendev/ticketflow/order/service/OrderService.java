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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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

    private final OrderRepository      orderRepository;
    private final TicketTypeRepository ticketTypeRepository;
    private final EventRepository      eventRepository;
    private final InventoryPort        inventoryPort;
    private final OrderStateMachine    orderStateMachine;
    private final StringRedisTemplate  redisTemplate;

    // Self-injection via @Lazy to route doCreateOrder() through the Spring proxy.
    // Direct this.doCreateOrder() bypasses the proxy — @Transactional has no effect,
    // no Hibernate Session is opened, and lazy associations throw LazyInitializationException.
    @Lazy
    @Autowired
    private OrderService self;

    private static final String   IDEMPOTENCY_KEY_PREFIX = "idempotency:";

    // PROCESSING TTL: short — in-flight signal only.
    // If JVM crashes before cleanup, key expires in 30s so the user can retry
    // rather than being permanently locked out.
    private static final Duration PROCESSING_TTL = Duration.ofSeconds(30);

    // Completed TTL: long — covers the user's reasonable retry window.
    // A requestId older than 24h will not produce a duplicate order in practice.
    private static final Duration COMPLETED_TTL = Duration.ofHours(24);

    private static final String PROCESSING = "PROCESSING";

    /**
     * Creates an order idempotently.
     *
     * NOT annotated with @Transactional. Redis idempotency check runs before
     * any DB transaction is opened. DB connections are only acquired inside
     * doCreateOrder(), preventing connection pool exhaustion during Redis I/O.
     *
     * Why no @Transactional here:
     * Holding a HikariCP connection during checkRedisIdempotency() (a network call
     * to Redis) wastes a connection slot. Under high concurrency, this reproduces
     * the connection pool exhaustion we hit in Step 1-H. The connection is acquired
     * only when doCreateOrder() actually needs to write to the DB.
     */
    public OrderResponse createOrder(Long userId, CreateOrderRequest request) {

        // ── Phase 1: Redis idempotency check (outside DB transaction) ─────────
        OrderResponse cached = checkRedisIdempotency(request.getRequestId());
        if (cached != null) {
            return cached;
        }

        // ── Phase 2: Core order creation (inside DB transaction via proxy) ────
        try {
            OrderResponse response = self.doCreateOrder(userId, request);
            markIdempotencyCompleted(request.getRequestId(), response.getOrderNo());
            return response;

        } catch (DataIntegrityViolationException e) {
            releaseIdempotencyKey(request.getRequestId());
            log.info("[Order] Idempotency enforced by DB constraint: requestId={}",
                    request.getRequestId());
            throw BizException.of(ResultCode.IDEMPOTENT_REJECTION,
                    "Duplicate request detected — order already exists");

        } catch (BizException e) {
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
            releaseIdempotencyKey(request.getRequestId());
            throw e;
        }
    }

    /**
     * Core order creation logic. Must be public so the Spring proxy can
     * intercept it for @Transactional. Called via self.doCreateOrder().
     */
    @Transactional(noRollbackFor = BizException.class)
    public OrderResponse doCreateOrder(Long userId, CreateOrderRequest request) {

        // DB fallback idempotency check — active when Redis was unavailable
        // and the SETNX fast path was skipped.
        if (orderRepository.existsByRequestId(request.getRequestId())) {
            log.info("[Order] Duplicate requestId detected via DB check: {}",
                    request.getRequestId());
            return orderRepository.findByRequestId(request.getRequestId())
                    .map(OrderResponse::from)
                    .orElseThrow(() -> BizException.of(ResultCode.ORDER_CREATE_FAILED,
                            "Idempotency key exists but order not found"));
        }

        TicketType ticketType = ticketTypeRepository.findById(request.getTicketTypeId())
                .orElseThrow(() -> BizException.of(ResultCode.TICKET_TYPE_NOT_FOUND,
                        "Ticket type not found: " + request.getTicketTypeId()));

        if (!ticketType.getEvent().isOnSale()) {
            throw BizException.of(ResultCode.EVENT_NOT_AVAILABLE,
                    "Event is not currently on sale");
        }

        // No pre-check (hasSufficientStock) — separate check introduces TOCTOU race.
        inventoryPort.deductStock(request.getTicketTypeId(), request.getQuantity());

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
                throw e;
            }
            log.error("[Order] Order save failed, releasing inventory: " +
                            "ticketTypeId={}, qty={}", request.getTicketTypeId(),
                    request.getQuantity());
            inventoryPort.releaseStock(request.getTicketTypeId(), request.getQuantity());
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

    @Transactional
    public void cancelOrderBySystem(String orderNo) {
        Order order = findOrderByNo(orderNo);
        orderStateMachine.handleEvent(order, OrderEvent.SYSTEM_TIMEOUT,
                "Cancelled by system timeout");
        inventoryPort.releaseStock(order.getTicketTypeId(), order.getQuantity());
        orderRepository.save(order);
        log.info("[Order] Cancelled by system timeout: orderNo={}", orderNo);
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    /**
     * Attempts to claim the idempotency slot via Redis SETNX.
     *
     * Returns a cached OrderResponse if this requestId was already processed,
     * or null if this is a new request that should proceed.
     *
     * Concurrency behaviour:
     *   Concurrent duplicates (simultaneous requests with same requestId):
     *     - First thread wins SETNX, proceeds to create order.
     *     - All other threads see PROCESSING and receive IDEMPOTENT_REJECTION (400).
     *     - This is correct: Fail-Fast preserves Tomcat thread pool capacity.
     *     - Sleeping while waiting would hold threads captive, causing pool exhaustion.
     *
     *   Sequential duplicates (retry after order is complete):
     *     - Key contains orderNo (COMPLETED_TTL = 24h).
     *     - Returns cached OrderResponse immediately. Zero DB write. Pure cache hit.
     *
     * Two-phase TTL design:
     *   PROCESSING (30s): short TTL prevents permanent deadlock on JVM crash.
     *   orderNo    (24h): long TTL serves sequential retries within reasonable window.
     */
    private OrderResponse checkRedisIdempotency(String requestId) {
        String key = IDEMPOTENCY_KEY_PREFIX + requestId;
        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(key, PROCESSING, PROCESSING_TTL);

            if (Boolean.TRUE.equals(acquired)) {
                return null; // First request — proceed with creation.
            }

            String value = redisTemplate.opsForValue().get(key);

            if (PROCESSING.equals(value)) {
                // Another request is in-flight. Fail-Fast: reject immediately.
                // Sleeping here would hold a Tomcat thread captive for 50-150ms.
                // Under high concurrency, this exhausts the thread pool and causes
                // a cascade failure where healthy requests cannot be served.
                log.info("[Order] Request in-flight, rejecting concurrent duplicate: " +
                        "requestId={}", requestId);
                throw BizException.of(ResultCode.IDEMPOTENT_REJECTION,
                        "Request is being processed — please try again shortly");
            }

            if (value != null) {
                // Order already created — return cached result. Pure Redis cache hit.
                log.info("[Order] Idempotency cache hit: requestId={}, orderNo={}",
                        requestId, value);
                return orderRepository.findByOrderNo(value)
                        .map(OrderResponse::from)
                        .orElse(null);
            }

            // Key expired between SET and GET — treat as new request.
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
