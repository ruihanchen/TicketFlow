package com.chendev.ticketflow.integration;

import com.chendev.ticketflow.IntegrationTestBase;
import com.chendev.ticketflow.event.entity.Event;
import com.chendev.ticketflow.event.entity.TicketType;
import com.chendev.ticketflow.event.repository.EventRepository;
import com.chendev.ticketflow.event.repository.TicketTypeRepository;
import com.chendev.ticketflow.inventory.entity.Inventory;
import com.chendev.ticketflow.inventory.redis.RedisInventoryManager;
import com.chendev.ticketflow.inventory.repository.InventoryRepository;
import com.chendev.ticketflow.order.dto.CreateOrderRequest;
import com.chendev.ticketflow.order.dto.OrderResponse;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end verification of the async inventory restoration loop:
 *   cancelOrder() → OrderCancelledEvent → Kafka → OrderCancelledConsumer → inventory +qty
 *
 * Uses Awaitility throughout — zero Thread.sleep().
 * For positive assertions: poll until condition holds (fast when consumer is quick).
 * For negative assertions (idempotency): pollDelay to give the consumer time to run,
 * then assert stock hasn't changed. Avoids hardcoding a fixed sleep duration.
 *
 * All three tests run against real Testcontainers (Postgres + Redis + Kafka),
 * inherited from IntegrationTestBase. No mocks, no in-memory substitutes.
 */
class KafkaInventoryRestoreTest extends IntegrationTestBase {

    @Autowired private OrderService          orderService;
    @Autowired private OrderTimeoutService   orderTimeoutService;
    @Autowired private OrderRepository       orderRepository;
    @Autowired private InventoryRepository   inventoryRepository;
    @Autowired private TicketTypeRepository  ticketTypeRepository;
    @Autowired private EventRepository       eventRepository;
    @Autowired private UserRepository        userRepository;
    @Autowired private PasswordEncoder       passwordEncoder;
    @Autowired private RedisInventoryManager redisInventoryManager;
    @Autowired private KafkaTemplate<String, OrderCancelledEvent> kafkaTemplate;

    @Value("${ticketflow.kafka.topics.order-cancelled:order.cancelled}")
    private String orderCancelledTopic;

    private static final int      INITIAL_STOCK    = 10;
    private static final Duration CONSUMER_TIMEOUT = Duration.ofSeconds(15);

    private Long ticketTypeId;
    private Long userId;

    @BeforeEach
    void setUp() {
        Event event = Event.create(
                "Kafka E2E Test Concert",
                "End-to-end async consumer test",
                "Test Venue",
                LocalDateTime.now().plusDays(30),
                LocalDateTime.now().minusHours(1),
                LocalDateTime.now().plusDays(29)
        );
        event.publish();
        event = eventRepository.save(event);

        TicketType ticketType = TicketType.create(
                event, "Standard", new BigDecimal("99.00"), INITIAL_STOCK
        );
        ticketType = ticketTypeRepository.save(ticketType);
        ticketTypeId = ticketType.getId();

        inventoryRepository.save(Inventory.initialize(ticketTypeId, INITIAL_STOCK));

        User user = User.create(
                "kafka_e2e_user",
                "kafka_e2e@test.com",
                passwordEncoder.encode("password")
        );
        userId = userRepository.save(user).getId();
    }

    /**
     * Happy path: user cancels → consumer restores inventory in Redis and DB.
     *
     * Verifies the full async loop and that cancellation does NOT synchronously
     * restore stock (Phase 2: async is the contract, not a side effect).
     */
    @Test
    void userCancel_inventoryRestoredAsyncViaKafka() {
        OrderResponse order = orderService.createOrder(userId,
                CreateOrderRequest.forTest(ticketTypeId, 1, UUID.randomUUID().toString()));

        assertThat(inventoryRepository.findByTicketTypeId(ticketTypeId)
                .orElseThrow().getAvailableStock())
                .as("Stock deducted synchronously on creation")
                .isEqualTo(INITIAL_STOCK - 1);

        orderService.cancelOrder(order.getOrderNo(), userId);

        assertThat(orderRepository.findByOrderNo(order.getOrderNo())
                .orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);

        // Verify async contract: stock not yet restored (consumer hasn't run).
        assertThat(inventoryRepository.findByTicketTypeId(ticketTypeId)
                .orElseThrow().getAvailableStock())
                .as("Phase 2: consumer restores async, not synchronously")
                .isEqualTo(INITIAL_STOCK - 1);

        // DB restored by consumer.
        await().atMost(CONSUMER_TIMEOUT)
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() ->
                        assertThat(inventoryRepository.findByTicketTypeId(ticketTypeId)
                                .orElseThrow().getAvailableStock())
                                .as("Consumer must restore DB stock to INITIAL_STOCK")
                                .isEqualTo(INITIAL_STOCK)
                );

        // Redis restored by Lua script inside the consumer.
        await().atMost(CONSUMER_TIMEOUT)
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() ->
                        assertThat(redisInventoryManager.getStock(ticketTypeId))
                                .as("Consumer must restore Redis stock to INITIAL_STOCK")
                                .hasValueSatisfying(stock ->
                                        assertThat(stock).isEqualTo(INITIAL_STOCK))
                );

        System.out.printf(
                "%n[KafkaE2E] userCancel: dbStock=%d, redisStock=%s%n",
                inventoryRepository.findByTicketTypeId(ticketTypeId)
                        .orElseThrow().getAvailableStock(),
                redisInventoryManager.getStock(ticketTypeId));
    }

    /**
     * System timeout: timeout service cancels → consumer restores inventory.
     */
    @Test
    void systemTimeout_inventoryRestoredAsyncViaKafka() {
        orderService.createOrder(userId,
                CreateOrderRequest.forTest(ticketTypeId, 1, UUID.randomUUID().toString()));

        Order order = orderRepository.findAll().get(0);
        order.expireNow();
        orderRepository.save(order);

        orderTimeoutService.cancelExpiredOrders();

        assertThat(orderRepository.findById(order.getId())
                .orElseThrow().getStatus())
                .isEqualTo(OrderStatus.CANCELLED);

        await().atMost(CONSUMER_TIMEOUT)
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() ->
                        assertThat(inventoryRepository.findByTicketTypeId(ticketTypeId)
                                .orElseThrow().getAvailableStock())
                                .as("Consumer must restore DB stock after timeout cancel")
                                .isEqualTo(INITIAL_STOCK)
                );

        System.out.printf(
                "%n[KafkaE2E] systemTimeout: dbStock=%d%n",
                inventoryRepository.findByTicketTypeId(ticketTypeId)
                        .orElseThrow().getAvailableStock());
    }

    /**
     * Idempotency: same messageId delivered twice restores inventory exactly once.
     *
     * Simulates Kafka redelivery during consumer rebalance. The second delivery
     * hits the DUPLICATE branch — Lua script finds the messageId key already set,
     * returns DUPLICATE, consumer skips the DB write and ACKs.
     *
     * Negative assertion uses Awaitility pollDelay:
     *   Give the consumer time to process the duplicate (pollDelay=3s),
     *   then assert stock is unchanged. No Thread.sleep().
     */
    @Test
    void sameMessageId_deliveredTwice_inventoryRestoredExactlyOnce() throws Exception {
        // Deduct stock by creating an order (simulates an existing pending order).
        orderService.createOrder(userId,
                CreateOrderRequest.forTest(ticketTypeId, 1, UUID.randomUUID().toString()));

        assertThat(inventoryRepository.findByTicketTypeId(ticketTypeId)
                .orElseThrow().getAvailableStock())
                .isEqualTo(INITIAL_STOCK - 1);

        // Build an event with a fixed messageId — we'll send it twice.
        String knownMessageId = UUID.randomUUID().toString();
        OrderCancelledEvent event = new OrderCancelledEvent(
                knownMessageId,
                "TF-IDEMPOTENCY-E2E-TEST",
                ticketTypeId,
                1,
                "idempotency e2e test",
                Instant.now()
        );

        // First delivery — consumer processes normally (OK path).
        kafkaTemplate.send(orderCancelledTopic, event.orderNo(), event).get();

        await().atMost(CONSUMER_TIMEOUT)
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() ->
                        assertThat(inventoryRepository.findByTicketTypeId(ticketTypeId)
                                .orElseThrow().getAvailableStock())
                                .isEqualTo(INITIAL_STOCK)
                );

        int stockAfterFirstDelivery = inventoryRepository
                .findByTicketTypeId(ticketTypeId).orElseThrow().getAvailableStock();

        // Second delivery — EXACT same event (same messageId).
        // Lua returns DUPLICATE; consumer skips DB and ACKs.
        kafkaTemplate.send(orderCancelledTopic, event.orderNo(), event).get();

        // Negative assertion: give consumer time to process the duplicate,
        // then verify stock did not change. pollDelay avoids hardcoded sleep.
        await().pollDelay(Duration.ofSeconds(3))
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() ->
                        assertThat(inventoryRepository.findByTicketTypeId(ticketTypeId)
                                .orElseThrow().getAvailableStock())
                                .as("Duplicate delivery must NOT restore inventory again. " +
                                        "If this fails, idempotency is broken.")
                                .isEqualTo(stockAfterFirstDelivery)
                );

        assertThat(redisInventoryManager.getStock(ticketTypeId))
                .as("Redis stock must also be unchanged after duplicate delivery")
                .hasValueSatisfying(stock ->
                        assertThat(stock).isEqualTo(INITIAL_STOCK));

        System.out.printf(
                "%n[KafkaE2E] idempotency: afterFirst=%d, afterDuplicate=%d (must be equal)%n",
                stockAfterFirstDelivery,
                inventoryRepository.findByTicketTypeId(ticketTypeId)
                        .orElseThrow().getAvailableStock());
    }
}
