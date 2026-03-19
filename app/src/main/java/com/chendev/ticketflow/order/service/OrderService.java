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

    // Self-injection via @Lazy to route doCreateOrder() through the Spring proxy.
    // Direct this.doCreateOrder() bypasses the proxy — @Transactional has no effect,
    // no Hibernate Session is opened, lazy associations throw LazyInitializationException.
    @Lazy
    @Autowired
    private OrderService self;

    private static final String   IDEMPOTENCY_KEY_PREFIX = "idempotency:";
    private static final Duration PROCESSING_TTL         = Duration.ofSeconds(30);
    private static final Duration COMPLETED_TTL          = Duration.ofHours(24);
    private static final String   PROCESSING             = "PROCESSING";

    /**
     * Creates an order idempotently.
     *
     * NOT annotated with @Transactional. Redis idempotency check runs before
     * any DB transaction is opened. DB connections are only acquired inside
     * doCreateOrder(), preventing connection pool exhaustion during Redis I/O.
     */
    public OrderResponse createOrder(Long userId, CreateOrderRequest request) {

        OrderResponse cached = checkRedisIdempotency(request.getRequestId());
        if (cached != null) {
            return cached;
        }

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
     * Core order creation. Must be public so the Spring proxy intercepts
     * @Transactional. Called via self.doCreateOrder().
     */
    @Transactional(noRollbackFor = BizException.class)
    public OrderResponse doCreateOrder(Long userId, CreateOrderRequest request) {

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

    /**
     * User-initiated cancellation.
     *
     * Phase 2: inventory is restored asynchronously via Kafka.
     * OrderCancelledEvent is published inside the transaction.
     * @TransactionalEventListener(AFTER_COMMIT) in OrderCancelledKafkaPublisher
     * forwards it to Kafka only after the DB commit succeeds — no ghost restores.
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
     * Accepts orderNo rather than an Order entity. This is intentional:
     * OrderTimeoutService loads expired Orders in its own (non-transactional)
     * context, making them detached entities. Passing a detached entity whose
     * lazy-loaded statusHistory collection is uninitialized would throw
     * LazyInitializationException when transitionTo() tries to add to that
     * collection. Loading fresh inside this @Transactional ensures the entity
     * is attached and all collections can be initialized on demand.
     *
     * Inventory release is async via Kafka, same as user cancellation.
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
