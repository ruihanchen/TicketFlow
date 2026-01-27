package com.chendev.ticketflow.order.entity;

import com.chendev.ticketflow.common.exception.DomainException;
import com.chendev.ticketflow.common.response.ResultCode;
import com.chendev.ticketflow.order.statemachine.OrderEvent;
import com.chendev.ticketflow.order.statemachine.OrderStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Pure domain unit test, no Spring context, runs in milliseconds.
// Covers every edge in TRANSITIONS (happy path) and every absent edge (invalid transitions).
class OrderStateMachineTest {

    // Production-length format so any future format validation in Order.create() surfaces here.
    private static final String TEST_ORDER_NO = "TF-00000000000000000000000000000001";
    private static final Duration CART_WINDOW = Duration.ofMinutes(15);

    @Test
    void created_to_paying() {
        Order order = createTestOrder();
        order.transitionTo(OrderStatus.PAYING, OrderEvent.INITIATE_PAYMENT, "pay");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYING);
    }

    @Test
    void created_to_cancelled_by_user() {
        Order order = createTestOrder();
        order.transitionTo(OrderStatus.CANCELLED, OrderEvent.CANCEL_BY_USER, "user cancel");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void paying_to_paid() {
        Order order = createTestOrder();
        order.transitionTo(OrderStatus.PAYING, OrderEvent.INITIATE_PAYMENT, "pay");
        order.transitionTo(OrderStatus.PAID, OrderEvent.PAYMENT_SUCCESS, "confirmed");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void paying_to_cancelled_by_timeout() {
        Order order = createTestOrder();
        order.transitionTo(OrderStatus.PAYING, OrderEvent.INITIATE_PAYMENT, "pay");
        order.transitionTo(OrderStatus.CANCELLED, OrderEvent.PAYMENT_TIMEOUT, "timeout");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void paid_to_confirmed() {
        Order order = createTestOrder();
        order.transitionTo(OrderStatus.PAYING, OrderEvent.INITIATE_PAYMENT, "pay");
        order.transitionTo(OrderStatus.PAID, OrderEvent.PAYMENT_SUCCESS, "paid");
        order.transitionTo(OrderStatus.CONFIRMED, OrderEvent.CONFIRM, "done");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void history_entry_records_correct_from_to_and_event() {
        // transitionTo() must record fromStatus BEFORE updating this.status.
        // If the order is wrong, fromStatus will equal the new status.
        Order order = createTestOrder();
        order.transitionTo(OrderStatus.PAYING, OrderEvent.INITIATE_PAYMENT, "init pay");

        assertThat(order.getStatusHistory()).hasSize(1);
        OrderStatusHistory entry = order.getStatusHistory().get(0);
        assertThat(entry.getFromStatus()).isEqualTo(OrderStatus.CREATED);
        assertThat(entry.getToStatus()).isEqualTo(OrderStatus.PAYING);
        assertThat(entry.getEvent()).isEqualTo(OrderEvent.INITIATE_PAYMENT);
        assertThat(entry.getReason()).isEqualTo("init pay");
    }

    @Test
    void history_accumulates_one_entry_per_transition() {
        Order order = createTestOrder();
        assertThat(order.getStatusHistory()).isEmpty();

        order.transitionTo(OrderStatus.PAYING, OrderEvent.INITIATE_PAYMENT, "p1");
        assertThat(order.getStatusHistory()).hasSize(1);

        order.transitionTo(OrderStatus.CANCELLED, OrderEvent.PAYMENT_TIMEOUT, "t1");
        assertThat(order.getStatusHistory()).hasSize(2);

        // second entry's fromStatus must be PAYING, not CREATED
        assertThat(order.getStatusHistory().get(1).getFromStatus()).isEqualTo(OrderStatus.PAYING);
    }

    @Test
    void total_amount_is_unit_price_times_quantity() {
        // Price snapshot: 3 × 49.99 = 149.97
        Order order = Order.create(TEST_ORDER_NO, 1L, 1L, 3,
                new BigDecimal("49.99"), "req-1", CART_WINDOW);
        assertThat(order.getTotalAmount()).isEqualByComparingTo(new BigDecimal("149.97"));
    }

    @Test
    void start_payment_window_rejects_second_call() {
        // Prevents extending the payment deadline by calling payOrder() twice.
        Order order = createTestOrder();
        order.startPaymentWindow(Duration.ofMinutes(5));

        assertThatThrownBy(() -> order.startPaymentWindow(Duration.ofMinutes(5)))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getResultCode())
                .isEqualTo(ResultCode.INVALID_STATE_TRANSITION);
    }

    @Test
    void payment_expired_at_is_null_before_payment_window_starts() {
        // isPaymentExpired() must return false on a fresh order, no payment attempt has started.
        assertThat(createTestOrder().isPaymentExpired()).isFalse();
    }

    @Test
    void is_cart_expired_returns_false_for_fresh_order() {
        assertThat(createTestOrder().isCartExpired()).isFalse();
    }

    @Test
    void expire_now_makes_is_cart_expired_true() {
        Order order = createTestOrder();
        order.expireNow();
        assertThat(order.isCartExpired()).isTrue();
    }

    @Test
    void expire_payment_now_does_not_affect_is_cart_expired() {
        // The two deadline fields are independent, expirePaymentNow() must not corrupt expiredAt.
        Order order = createTestOrder();
        order.startPaymentWindow(Duration.ofMinutes(5));
        order.expirePaymentNow();

        assertThat(order.isCartExpired()).isFalse();
        assertThat(order.isPaymentExpired()).isTrue();
    }

    @Test
    void expire_now_does_not_affect_payment_expired_at() {
        // Inverse: expireNow() must not touch paymentExpiredAt.
        Order order = createTestOrder();
        order.startPaymentWindow(Duration.ofMinutes(5));
        order.expireNow();

        assertThat(order.isCartExpired()).isTrue();
        assertThat(order.isPaymentExpired()).isFalse();
    }

    static Stream<Arguments> invalidTransitions() {
        return Stream.of(
                Arguments.of(OrderStatus.CREATED,   OrderStatus.PAID),
                Arguments.of(OrderStatus.CREATED,   OrderStatus.CONFIRMED),
                Arguments.of(OrderStatus.PAYING,    OrderStatus.CREATED),
                Arguments.of(OrderStatus.PAID,      OrderStatus.PAYING),
                Arguments.of(OrderStatus.PAID,      OrderStatus.CREATED),
                Arguments.of(OrderStatus.PAID,      OrderStatus.CANCELLED),
                Arguments.of(OrderStatus.CONFIRMED, OrderStatus.CANCELLED),
                Arguments.of(OrderStatus.CONFIRMED, OrderStatus.CREATED),
                Arguments.of(OrderStatus.CONFIRMED, OrderStatus.PAYING),
                Arguments.of(OrderStatus.CONFIRMED, OrderStatus.PAID),
                Arguments.of(OrderStatus.CANCELLED, OrderStatus.CREATED),
                Arguments.of(OrderStatus.CANCELLED, OrderStatus.PAYING),
                Arguments.of(OrderStatus.CANCELLED, OrderStatus.PAID),
                Arguments.of(OrderStatus.CANCELLED, OrderStatus.CONFIRMED)
        );
    }

    @ParameterizedTest(name = "{0} → {1} must be rejected")
    @MethodSource("invalidTransitions")
    void invalid_transition_throws_with_correct_result_code(OrderStatus from, OrderStatus to) {
        Order order = createTestOrder();
        advanceTo(order, from);

        assertThatThrownBy(() -> order.transitionTo(to, OrderEvent.CANCEL_BY_USER, "test"))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getResultCode())
                .isEqualTo(ResultCode.INVALID_STATE_TRANSITION);
    }

    private static Order createTestOrder() {
        return Order.create(TEST_ORDER_NO, 1L, 1L, 1,
                new BigDecimal("100.00"), "req-test", CART_WINDOW);
    }

    // Walks to target via the shortest valid path.
    private static void advanceTo(Order order, OrderStatus target) {
        if (target == OrderStatus.CREATED) return;
        if (target == OrderStatus.CANCELLED) {
            order.transitionTo(OrderStatus.CANCELLED, OrderEvent.CANCEL_BY_USER, "setup");
            return;
        }
        order.transitionTo(OrderStatus.PAYING, OrderEvent.INITIATE_PAYMENT, "setup");
        if (target == OrderStatus.PAYING) return;
        order.transitionTo(OrderStatus.PAID, OrderEvent.PAYMENT_SUCCESS, "setup");
        if (target == OrderStatus.PAID) return;
        order.transitionTo(OrderStatus.CONFIRMED, OrderEvent.CONFIRM, "setup");
    }
}