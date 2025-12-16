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

// no Spring context -- runs in milliseconds
class OrderStateMachineTest {


    @Test
    void created_to_paying() {
        Order order = createTestOrder();
        order.transitionTo(OrderStatus.PAYING, OrderEvent.INITIATE_PAYMENT, "test");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYING);
    }

    @Test
    void created_to_cancelled() {
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
    void paying_to_cancelled() {
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

    static Stream<Arguments> invalidTransitions() {
        return Stream.of(
                //can't skip PAYING
                Arguments.of(OrderStatus.CREATED, OrderStatus.PAID),
                Arguments.of(OrderStatus.CREATED, OrderStatus.CONFIRMED),

                //can't go backwards
                Arguments.of(OrderStatus.PAYING, OrderStatus.CREATED),
                Arguments.of(OrderStatus.PAID, OrderStatus.PAYING),
                Arguments.of(OrderStatus.PAID, OrderStatus.CREATED),// also can't skip back past PAYING

                //terminal states — no way out
                Arguments.of(OrderStatus.CONFIRMED, OrderStatus.CANCELLED),
                Arguments.of(OrderStatus.CANCELLED, OrderStatus.CREATED),
                Arguments.of(OrderStatus.CANCELLED, OrderStatus.PAYING),
                Arguments.of(OrderStatus.CANCELLED, OrderStatus.PAID)
        );
    }

    @ParameterizedTest(name = "{0} -> {1} should be rejected")
    @MethodSource("invalidTransitions")
    void invalid_transition_throws(OrderStatus from, OrderStatus to) {
        Order order = createTestOrder();
        advanceTo(order, from);

        assertThatThrownBy(() ->
                order.transitionTo(to, OrderEvent.CANCEL_BY_USER, "test"))
                .isInstanceOf(DomainException.class)
                .extracting(e -> ((DomainException) e).getResultCode())
                .isEqualTo(ResultCode.INVALID_STATE_TRANSITION);
    }


    @Test
    void transition_records_history_entry() {
        Order order = createTestOrder();
        assertThat(order.getStatusHistory()).isEmpty();

        order.transitionTo(OrderStatus.PAYING, OrderEvent.INITIATE_PAYMENT, "pay");
        assertThat(order.getStatusHistory()).hasSize(1);

        order.transitionTo(OrderStatus.CANCELLED, OrderEvent.PAYMENT_TIMEOUT, "timeout");
        assertThat(order.getStatusHistory()).hasSize(2);
    }


    private static Order createTestOrder() {
        return Order.create("TF-TEST-001", 1L, 1L, 1,
                new BigDecimal("100.00"), "req-test", Duration.ofMinutes(15));
    }

    // walks to target via shortest valid path; only needed for invalid_transition_throws
    private static void advanceTo(Order order, OrderStatus target) {
        if (target == OrderStatus.CREATED) return;
        if (target == OrderStatus.CANCELLED) {
            order.transitionTo(OrderStatus.CANCELLED, OrderEvent.CANCEL_BY_USER, "test");
            return;
        }
        order.transitionTo(OrderStatus.PAYING, OrderEvent.INITIATE_PAYMENT, "test");
        if (target == OrderStatus.PAYING) return;
        order.transitionTo(OrderStatus.PAID, OrderEvent.PAYMENT_SUCCESS, "test");
        if (target == OrderStatus.PAID) return;
        order.transitionTo(OrderStatus.CONFIRMED, OrderEvent.CONFIRM, "test");
    }
}