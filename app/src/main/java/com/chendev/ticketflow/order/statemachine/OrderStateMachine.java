package com.chendev.ticketflow.order.statemachine;

import com.chendev.ticketflow.common.exception.BizException;
import com.chendev.ticketflow.common.response.ResultCode;
import com.chendev.ticketflow.order.entity.Order;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;


@Slf4j
@Component
public class OrderStateMachine {
    // (CurrentStatus, Event) → NextStatus
    // Single source of truth for all legal transitions
    private static final Map<OrderStatus, Map<OrderEvent, OrderStatus>> TRANSITIONS =
            new EnumMap<>(OrderStatus.class);

    static {
        Map<OrderEvent, OrderStatus> fromCreated = new EnumMap<>(OrderEvent.class);
        fromCreated.put(OrderEvent.INITIATE_PAYMENT, OrderStatus.PAYING);
        fromCreated.put(OrderEvent.CANCEL_BY_USER,   OrderStatus.CANCELLED);
        fromCreated.put(OrderEvent.SYSTEM_TIMEOUT,   OrderStatus.CANCELLED);
        TRANSITIONS.put(OrderStatus.CREATED, fromCreated);

        Map<OrderEvent, OrderStatus> fromPaying = new EnumMap<>(OrderEvent.class);
        fromPaying.put(OrderEvent.PAYMENT_SUCCESS, OrderStatus.PAID);
        fromPaying.put(OrderEvent.PAYMENT_FAIL,    OrderStatus.CANCELLED);
        fromPaying.put(OrderEvent.PAYMENT_TIMEOUT, OrderStatus.CANCELLED);
        TRANSITIONS.put(OrderStatus.PAYING, fromPaying);

        Map<OrderEvent, OrderStatus> fromPaid = new EnumMap<>(OrderEvent.class);
        fromPaid.put(OrderEvent.CONFIRM_TICKET, OrderStatus.CONFIRMED);
        TRANSITIONS.put(OrderStatus.PAID, fromPaid);

        // Terminal states — no outgoing transitions
        TRANSITIONS.put(OrderStatus.CONFIRMED, new EnumMap<>(OrderEvent.class));
        TRANSITIONS.put(OrderStatus.CANCELLED, new EnumMap<>(OrderEvent.class));
    }

    /**
     * Drives a state transition on the given order.
     *
     * Transaction boundary is owned by OrderService, not here.
     * StateMachine is pure domain logic — validate and execute only.
     *
     * @param order  the order entity to transition
     * @param event  the business event triggering the transition
     * @param reason human-readable reason, recorded in status history
     * @return the new status after transition
     */
    public OrderStatus handleEvent(Order order, OrderEvent event, String reason) {
        OrderStatus currentStatus = order.getStatus();
        OrderStatus nextStatus = resolve(currentStatus, event);

        if (nextStatus == null) {
            log.warn("[StateMachine] Illegal transition: order=[{}] status=[{}] event=[{}]",
                    order.getOrderNo(), currentStatus, event);
            throw new BizException(ResultCode.ORDER_STATUS_INVALID,
                    String.format("Cannot apply event [%s] to order [%s] in status [%s]",
                            event, order.getOrderNo(), currentStatus));
        }

        // Delegate to Order entity — entity owns its own state mutation
        // History is recorded inside transitionTo() via cascade
        order.transitionTo(nextStatus, event, buildReason(event, reason));

        log.info("[StateMachine] Order [{}]: [{}] --{}--> [{}]",
                order.getOrderNo(), currentStatus, event, nextStatus);

        return nextStatus;
    }

    /**
     * Pure query — does not mutate any state.
     * Useful for returning available actions to the frontend.
     */
    public OrderStatus resolve(OrderStatus current, OrderEvent event) {
        Map<OrderEvent, OrderStatus> allowed = TRANSITIONS.get(current);
        return allowed != null ? allowed.get(event) : null;
    }

    public boolean isTerminalState(OrderStatus status) {
        Map<OrderEvent, OrderStatus> allowed = TRANSITIONS.get(status);
        return allowed != null && allowed.isEmpty();
    }

    private String buildReason(OrderEvent event, String detail) {
        return detail != null
                ? String.format("[%s] %s", event.name(), detail)
                : String.format("[%s]", event.name());
    }
}
