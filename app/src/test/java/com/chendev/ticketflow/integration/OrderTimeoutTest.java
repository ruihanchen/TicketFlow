package com.chendev.ticketflow.integration;

import com.chendev.ticketflow.IntegrationTestBase;
import com.chendev.ticketflow.event.entity.Event;
import com.chendev.ticketflow.event.entity.TicketType;
import com.chendev.ticketflow.event.repository.EventRepository;
import com.chendev.ticketflow.event.repository.TicketTypeRepository;
import com.chendev.ticketflow.inventory.entity.Inventory;
import com.chendev.ticketflow.inventory.repository.InventoryRepository;
import com.chendev.ticketflow.order.dto.CreateOrderRequest;
import com.chendev.ticketflow.order.entity.Order;
import com.chendev.ticketflow.order.event.OrderCancelledEvent;
import com.chendev.ticketflow.order.repository.OrderRepository;
import com.chendev.ticketflow.order.service.OrderService;
import com.chendev.ticketflow.order.service.OrderTimeoutService;
import com.chendev.ticketflow.order.statemachine.OrderStatus;
import com.chendev.ticketflow.user.entity.User;
import com.chendev.ticketflow.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test 3: Order Timeout Cancellation
 *
 * Validates three correctness invariants of the timeout mechanism:
 *   1. Expired orders  → CANCELLED, OrderCancelledEvent published to Spring context
 *   2. Active orders   → CREATED,   no event published, inventory unchanged
 *   3. Running the timeout service twice is idempotent — state machine rejects
 *      the second attempt; exactly zero events are published on the second run.
 *
 * Phase 2 behaviour (inventory restoration is async):
 *   cancelOrderBySystem() publishes an OrderCancelledEvent via ApplicationEventPublisher.
 *   OrderCancelledKafkaPublisher forwards it to Kafka AFTER_COMMIT (@Async).
 *   The Kafka consumer (Step 3-C) restores inventory asynchronously.
 *
 *   These tests verify the cancellation + event publication side only.
 *   The full async restoration loop is tested in Step 3-D (KafkaConsumerTest).
 *
 * Why @RecordApplicationEvents?
 *   @TransactionalEventListener(@Async) makes Kafka send happen on a background
 *   thread after commit. Testing the actual Kafka delivery would require waiting
 *   for async completion — slow and flaky. Instead, we intercept Spring's
 *   ApplicationEventPublisher bus directly. This is millisecond-fast and 100%
 *   deterministic: if publishEvent() was called, the event is captured regardless
 *   of what the listener does with it. The listener's Kafka delivery is tested
 *   end-to-end in Step 3-D.
 *
 * Three-dimensional assertion:
 *   1. DB state    — order status transitioned correctly
 *   2. Negative    — stock not synchronously restored (async responsibility)
 *   3. Positive    — OrderCancelledEvent published exactly once, with correct data
 */
@RecordApplicationEvents
class OrderTimeoutTest extends IntegrationTestBase {

    @Autowired private OrderService         orderService;
    @Autowired private OrderTimeoutService  orderTimeoutService;
    @Autowired private OrderRepository      orderRepository;
    @Autowired private InventoryRepository  inventoryRepository;
    @Autowired private TicketTypeRepository ticketTypeRepository;
    @Autowired private EventRepository      eventRepository;
    @Autowired private UserRepository       userRepository;
    @Autowired private PasswordEncoder      passwordEncoder;
    @Autowired private ApplicationEvents    applicationEvents;

    private static final int INITIAL_STOCK = 10;

    private Long ticketTypeId;
    private Long userId;

    @BeforeEach
    void setUp() {
        // IntegrationTestBase.cleanDatabase() handles TRUNCATE + Redis FLUSHDB.

        Event event = Event.create(
                "Timeout Test Concert",
                "Tests for order expiration logic",
                "Test Venue",
                LocalDateTime.now().plusDays(30),
                LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusDays(29)
        );
        event.publish();
        event = eventRepository.save(event);

        TicketType ticketType = TicketType.create(
                event, "Standard", new BigDecimal("50.00"), INITIAL_STOCK
        );
        ticketType = ticketTypeRepository.save(ticketType);
        ticketTypeId = ticketType.getId();

        inventoryRepository.save(Inventory.initialize(ticketTypeId, INITIAL_STOCK));

        User user = User.create(
                "timeout_test_user",
                "timeout@test.com",
                passwordEncoder.encode("password")
        );
        userId = userRepository.save(user).getId();
    }

    /**
     * Three-dimensional verification:
     *   1. DB: expired order transitions to CANCELLED.
     *   2. Negative: stock not synchronously restored (async, consumer in Step 3-C).
     *   3. Positive: exactly one OrderCancelledEvent published with correct orderNo.
     */
    @Test
    void expiredOrder_isAutoCancelled_andKafkaEventPublished() {
        // ── Arrange ───────────────────────────────────────────────────────────

        orderService.createOrder(userId,
                CreateOrderRequest.forTest(ticketTypeId, 1, UUID.randomUUID().toString()));

        int stockAfterOrder = inventoryRepository
                .findByTicketTypeId(ticketTypeId).orElseThrow()
                .getAvailableStock();
        assertThat(stockAfterOrder)
                .as("Stock must be deducted immediately after order creation")
                .isEqualTo(INITIAL_STOCK - 1);

        Order order = orderRepository.findAll().get(0);
        order.expireNow();
        orderRepository.save(order);

        // ── Act ───────────────────────────────────────────────────────────────

        orderTimeoutService.cancelExpiredOrders();

        // ── Assert 1: DB state ────────────────────────────────────────────────

        Order cancelledOrder = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(cancelledOrder.getStatus())
                .as("Expired order must be CANCELLED by the timeout service")
                .isEqualTo(OrderStatus.CANCELLED);

        // ── Assert 2: Negative — no synchronous stock restore ─────────────────

        int stockAfterCancellation = inventoryRepository
                .findByTicketTypeId(ticketTypeId).orElseThrow()
                .getAvailableStock();
        assertThat(stockAfterCancellation)
                .as("Phase 2: inventory restored async by consumer (Step 3-C). " +
                        "Stock must remain INITIAL_STOCK - 1 immediately after cancellation.")
                .isEqualTo(INITIAL_STOCK - 1);

        // ── Assert 3: Positive — event published exactly once ─────────────────
        // @RecordApplicationEvents captures events published to Spring's
        // ApplicationEventPublisher synchronously, before the @Async listener runs.
        // This is the contract verification: cancelOrderBySystem() must publish
        // exactly one OrderCancelledEvent carrying the correct orderNo.

        long eventCount = applicationEvents.stream(OrderCancelledEvent.class).count();
        assertThat(eventCount)
                .as("Exactly one OrderCancelledEvent must be published to the " +
                        "Spring ApplicationContext — the async Kafka publisher listens for this")
                .isEqualTo(1);

        OrderCancelledEvent publishedEvent = applicationEvents
                .stream(OrderCancelledEvent.class)
                .findFirst()
                .orElseThrow();
        assertThat(publishedEvent.orderNo())
                .as("Published event must carry the correct orderNo")
                .isEqualTo(cancelledOrder.getOrderNo());
        assertThat(publishedEvent.ticketTypeId())
                .as("Published event must carry the correct ticketTypeId")
                .isEqualTo(ticketTypeId);
        assertThat(publishedEvent.quantity())
                .as("Published event must carry the correct quantity")
                .isEqualTo(1);

        System.out.printf(
                "%n[Timeout Test] 3D verified: status=%s, stock=%d, " +
                        "events=%d, orderNo=%s%n",
                cancelledOrder.getStatus(), stockAfterCancellation,
                eventCount, publishedEvent.orderNo());
    }

    @Test
    void activeOrder_isNotCancelled_byTimeoutService() {
        // ── Arrange ───────────────────────────────────────────────────────────

        orderService.createOrder(userId,
                CreateOrderRequest.forTest(ticketTypeId, 1, UUID.randomUUID().toString()));

        // ── Act ───────────────────────────────────────────────────────────────

        orderTimeoutService.cancelExpiredOrders();

        // ── Assert ────────────────────────────────────────────────────────────

        Order activeOrder = orderRepository.findAll().get(0);
        assertThat(activeOrder.getStatus())
                .as("Non-expired order must remain CREATED after timeout service runs")
                .isEqualTo(OrderStatus.CREATED);

        int stock = inventoryRepository
                .findByTicketTypeId(ticketTypeId).orElseThrow()
                .getAvailableStock();
        assertThat(stock)
                .as("Inventory must not be touched for a non-expired order")
                .isEqualTo(INITIAL_STOCK - 1);

        long eventCount = applicationEvents.stream(OrderCancelledEvent.class).count();
        assertThat(eventCount)
                .as("No OrderCancelledEvent must be published for a non-expired order")
                .isEqualTo(0);

        System.out.printf(
                "%n[Timeout Test] activeOrder_preserved: status=%s, stock=%d, events=%d%n",
                activeOrder.getStatus(), stock, eventCount);
    }

    /**
     * Idempotency verification — three dimensions:
     *   1. DB: exactly one CANCELLED order after two runs.
     *   2. Events from first run: exactly one OrderCancelledEvent.
     *   3. Events from second run: exactly zero (state machine blocked the transition).
     *
     * The zero-event assertion on the second run proves the consumer will not
     * receive a duplicate message — preventing double inventory restoration.
     */
    @Test
    void timeoutService_isIdempotent_runningTwiceHasNoAdditionalEffect()
            throws InterruptedException {
        // ── Arrange ───────────────────────────────────────────────────────────

        orderService.createOrder(userId,
                CreateOrderRequest.forTest(ticketTypeId, 1, UUID.randomUUID().toString()));

        Order order = orderRepository.findAll().get(0);
        order.expireNow();
        orderRepository.save(order);

        // ── Act: first run ────────────────────────────────────────────────────

        orderTimeoutService.cancelExpiredOrders();

        long eventsAfterFirstRun = applicationEvents.stream(OrderCancelledEvent.class).count();
        assertThat(eventsAfterFirstRun)
                .as("First run must publish exactly one OrderCancelledEvent")
                .isEqualTo(1);

        // Clear recorded events so the second run's output is isolated.
        applicationEvents.clear();

        // ── Act: second run ───────────────────────────────────────────────────

        orderTimeoutService.cancelExpiredOrders();

        // ── Assert ────────────────────────────────────────────────────────────

        long cancelledCount = orderRepository.findAll().stream()
                .filter(o -> o.getStatus() == OrderStatus.CANCELLED)
                .count();
        assertThat(cancelledCount)
                .as("Exactly one CANCELLED order — state machine rejects duplicate transitions")
                .isEqualTo(1);

        int stock = inventoryRepository
                .findByTicketTypeId(ticketTypeId).orElseThrow()
                .getAvailableStock();
        assertThat(stock)
                .as("Phase 2: stock unchanged — async consumer has not run yet")
                .isEqualTo(INITIAL_STOCK - 1);

        long eventsAfterSecondRun = applicationEvents.stream(OrderCancelledEvent.class).count();
        assertThat(eventsAfterSecondRun)
                .as("Second run must publish ZERO events — state machine blocked the " +
                        "SYSTEM_TIMEOUT transition for an already-CANCELLED order. " +
                        "This prevents duplicate Kafka messages and double inventory restore.")
                .isEqualTo(0);

        System.out.printf(
                "%n[Timeout Test] idempotency verified: cancelled=%d, stock=%d, " +
                        "events_run1=%d, events_run2=%d%n",
                cancelledCount, stock, eventsAfterFirstRun, eventsAfterSecondRun);
    }
}
