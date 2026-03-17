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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test 3: Order Timeout Cancellation
 *
 * Validates three correctness invariants of the timeout mechanism:
 *   1. Expired orders  → CANCELLED, inventory fully restored
 *   2. Active orders   → CREATED,   inventory unchanged
 *   3. Running the timeout service twice is idempotent — no double-cancellation,
 *      no double inventory restore
 *
 * Design note: we backdate expiredAt rather than sleeping, so the test
 * completes in milliseconds and is fully deterministic.
 */
class OrderTimeoutTest extends IntegrationTestBase {

    @Autowired private OrderService orderService;
    @Autowired private OrderTimeoutService orderTimeoutService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private InventoryRepository inventoryRepository;
    @Autowired private TicketTypeRepository ticketTypeRepository;
    @Autowired private EventRepository eventRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private static final int INITIAL_STOCK = 10;

    private Long ticketTypeId;
    private Long userId;

    @BeforeEach
    void setUp() {
        // Base class @BeforeEach already TRUNCATEs all tables.
        // We only insert the fixture data needed for this test.

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
                event,
                "Standard",
                new BigDecimal("50.00"),
                INITIAL_STOCK
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

    @Test
    void expiredOrder_isAutoCancelled_andInventoryIsRestored() {
        // ── Arrange ───────────────────────────────────────────────────────────

        // Create a normal order through the service layer.
        // available_stock is now INITIAL_STOCK - 1.
        orderService.createOrder(userId,
                CreateOrderRequest.forTest(ticketTypeId, 1, UUID.randomUUID().toString()));

        // Sanity check: stock was deducted before we manipulate expiry.
        int stockAfterOrder = inventoryRepository
                .findByTicketTypeId(ticketTypeId).orElseThrow()
                .getAvailableStock();
        assertThat(stockAfterOrder)
                .as("Stock must be deducted immediately after order creation")
                .isEqualTo(INITIAL_STOCK - 1);

        // Backdate expiredAt to simulate 15 minutes having elapsed.
        // We manipulate the DB directly rather than sleeping for 15 minutes.
        Order order = orderRepository.findAll().get(0);
        order.expireNow(); // sets expiredAt = now - 1 second
        orderRepository.save(order);

        // ── Act ───────────────────────────────────────────────────────────────

        orderTimeoutService.cancelExpiredOrders();

        // ── Assert ────────────────────────────────────────────────────────────

        Order cancelledOrder = orderRepository.findById(order.getId()).orElseThrow();

        assertThat(cancelledOrder.getStatus())
                .as("Expired order must be CANCELLED by the timeout service")
                .isEqualTo(OrderStatus.CANCELLED);

        int stockAfterCancellation = inventoryRepository
                .findByTicketTypeId(ticketTypeId).orElseThrow()
                .getAvailableStock();

        assertThat(stockAfterCancellation)
                .as("Inventory must be fully restored after timeout cancellation")
                .isEqualTo(INITIAL_STOCK);

        System.out.printf(
                "%n[Timeout Test] expiredOrder_cancelled: status=%s, stock=%d%n",
                cancelledOrder.getStatus(), stockAfterCancellation);
    }

    @Test
    void activeOrder_isNotCancelled_byTimeoutService() {
        // ── Arrange ───────────────────────────────────────────────────────────

        // Create an order with default expiredAt = now + 15 min. It has NOT expired.
        orderService.createOrder(userId,
                CreateOrderRequest.forTest(ticketTypeId, 1, UUID.randomUUID().toString()));

        // ── Act ───────────────────────────────────────────────────────────────

        // Run the timeout service — it must leave this active order untouched.
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
                .as("Inventory must not be restored for a non-expired order")
                .isEqualTo(INITIAL_STOCK - 1);

        System.out.printf(
                "%n[Timeout Test] activeOrder_preserved: status=%s, stock=%d%n",
                activeOrder.getStatus(), stock);
    }

    @Test
    void timeoutService_isIdempotent_runningTwiceHasNoAdditionalEffect() {
        // ── Arrange ───────────────────────────────────────────────────────────

        orderService.createOrder(userId,
                CreateOrderRequest.forTest(ticketTypeId, 1, UUID.randomUUID().toString()));

        Order order = orderRepository.findAll().get(0);
        order.expireNow();
        orderRepository.save(order);

        // ── Act ───────────────────────────────────────────────────────────────

        // First run: cancels the order and restores inventory.
        orderTimeoutService.cancelExpiredOrders();

        // Second run: order is already CANCELLED.
        // The state machine must reject the SYSTEM_TIMEOUT event for a CANCELLED
        // order, which cancelOrderBySystem() catches and logs as a warning.
        // Inventory must NOT be released a second time.
        orderTimeoutService.cancelExpiredOrders();

        // ── Assert ────────────────────────────────────────────────────────────

        long cancelledCount = orderRepository.findAll().stream()
                .filter(o -> o.getStatus() == OrderStatus.CANCELLED)
                .count();

        assertThat(cancelledCount)
                .as("Exactly one CANCELLED order — no state duplication")
                .isEqualTo(1);

        int stock = inventoryRepository
                .findByTicketTypeId(ticketTypeId).orElseThrow()
                .getAvailableStock();

        assertThat(stock)
                .as("Inventory restored exactly once — no double-restore")
                .isEqualTo(INITIAL_STOCK);

        System.out.printf(
                "%n[Timeout Test] idempotency_verified: cancelled=%d, stock=%d%n",
                cancelledCount, stock);
    }
}
