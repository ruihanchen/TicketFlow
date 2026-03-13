# ADR-005: Event-Driven Order State Machine

**Date**: 2026-03  
**Status**: Accepted  
**Author**: Chen (chendev)

---

## Context

An order in TicketFlow moves through a defined lifecycle:

```
CREATED → PAYING → PAID → CONFIRMED
    ↘         ↘
    CANCELLED  CANCELLED
```

Each transition is triggered by a business event: user initiates payment,
payment gateway confirms success, system timeout fires, user cancels.

The question is how to implement this lifecycle in a way that is correct today
and maintainable as the system grows.

---

## The If-Else Anti-Pattern

The instinctive implementation scatters state transition logic across service methods:

```java
public void payOrder(String orderNo) {
    Order order = findOrder(orderNo);
    if (order.getStatus() == OrderStatus.CREATED) {
        order.setStatus(OrderStatus.PAYING);
    } else {
        throw new BizException(ResultCode.ORDER_STATUS_INVALID);
    }
}

public void confirmPayment(String orderNo) {
    Order order = findOrder(orderNo);
    if (order.getStatus() == OrderStatus.PAYING) {
        order.setStatus(OrderStatus.PAID);
        order.setStatus(OrderStatus.CONFIRMED);
    } else {
        throw new BizException(ResultCode.ORDER_STATUS_INVALID);
    }
}
```

This works for five states. It becomes unmaintainable at ten. The problems:

1. **Scattered truth**: Legal transitions are defined implicitly across multiple methods.
   There is no single place to answer "what transitions are legal from PAYING?"
2. **Silent gaps**: Adding a new state requires hunting through every service method
   to find which ones need updating. Missing one is a silent bug.
3. **No audit trail**: Status changes are direct field mutations. There is no record
   of what triggered each transition, when, or why.
4. **No event semantics**: "The payment succeeded" and "the user cancelled" both result
   in state changes, but the code treats them identically — a status update.
   The business meaning of the trigger is lost.

---

## Options Considered

### Option A: If-else per service method (described above)

**Rejected**: Does not scale. Transition logic is implicit and scattered.

### Option B: State machine with status-to-status mapping

```java
Map<OrderStatus, Set<OrderStatus>> TRANSITIONS = new EnumMap<>(OrderStatus.class);
// CREATED → {PAYING, CANCELLED}
// PAYING  → {PAID, CANCELLED}
// etc.
```

**Pros**: Centralizes legal transitions. Illegal transitions are structurally impossible.  
**Cons**: Only captures *that* a transition happened, not *why*. "PAYING → CANCELLED"
could be user cancellation, payment failure, or timeout — the same transition for three
different business reasons. Audit logs cannot distinguish them.

### Option C: Event-driven state machine with (Status, Event) → Status mapping

```java
Map<OrderStatus, Map<OrderEvent, OrderStatus>> TRANSITIONS;
// CREATED  + INITIATE_PAYMENT → PAYING
// CREATED  + CANCEL_BY_USER   → CANCELLED
// CREATING + SYSTEM_TIMEOUT   → CANCELLED
// PAYING   + PAYMENT_SUCCESS  → PAID
// PAYING   + PAYMENT_FAIL     → CANCELLED
// PAYING   + PAYMENT_TIMEOUT  → CANCELLED
// PAID     + CONFIRM_TICKET   → CONFIRMED
```

**Pros**:
- Every transition requires both a current state *and* a triggering event
- The event is recorded in `order_status_history`, creating a full audit trail
- Business semantics are explicit: PAYMENT_FAIL and SYSTEM_TIMEOUT both lead to
  CANCELLED, but for different reasons — both are recorded
- Illegal transitions are impossible: any (Status, Event) pair not in the map is rejected

**Cons**: More initial complexity. `OrderEvent` enum must be maintained alongside
`OrderStatus` enum.

---

## Decision

**Option C: Event-driven state machine.**

The `OrderStateMachine` is the single source of truth for all legal transitions.
It is a pure domain component — no `@Transactional`, no repository access,
no infrastructure dependencies.

```java
@Component
public class OrderStateMachine {

    private static final Map<OrderStatus, Map<OrderEvent, OrderStatus>> TRANSITIONS =
            new EnumMap<>(OrderStatus.class);

    static {
        Map<OrderEvent, OrderStatus> fromCreated = new EnumMap<>(OrderEvent.class);
        fromCreated.put(OrderEvent.INITIATE_PAYMENT, OrderStatus.PAYING);
        fromCreated.put(OrderEvent.CANCEL_BY_USER,   OrderStatus.CANCELLED);
        fromCreated.put(OrderEvent.SYSTEM_TIMEOUT,   OrderStatus.CANCELLED);
        TRANSITIONS.put(OrderStatus.CREATED, fromCreated);
        // ...
    }

    public OrderStatus handleEvent(Order order, OrderEvent event, String reason) {
        OrderStatus nextStatus = resolve(order.getStatus(), event);
        if (nextStatus == null) {
            throw new BizException(ResultCode.ORDER_STATUS_INVALID, ...);
        }
        order.transitionTo(nextStatus, event, reason);  // Records history internally
        return nextStatus;
    }
}
```

**Transaction ownership stays in `OrderService`**, not in the state machine.
The state machine validates and executes. The service persists and compensates.

---

## The Audit Trail Design

Every call to `order.transitionTo(newStatus, event, reason)` automatically appends
a record to `order_status_history`:

```
order_id | from_status | to_status | event           | reason
---------|-------------|-----------|-----------------|---------------------------
1        | CREATED     | PAYING    | INITIATE_PAYMENT| Payment initiated by user
1        | PAYING      | CANCELLED | PAYMENT_TIMEOUT | Payment window expired
```

This audit trail answers production questions that would otherwise require
log archaeology:
- "Why was this order cancelled?" → `event = PAYMENT_TIMEOUT`
- "Did the user ever try to pay?" → `from_status = CREATED, event = INITIATE_PAYMENT`
- "How long did it take from creation to confirmation?" → `created_at` timestamps

`OrderStatusHistory.record()` is package-private. Only `Order.transitionTo()` can
call it. External code cannot create history records without going through the state
machine — the audit trail cannot be bypassed.

---

## Terminal State Protection

`CONFIRMED` and `CANCELLED` are terminal states. The state machine defines them
with empty event maps:

```java
TRANSITIONS.put(OrderStatus.CONFIRMED, new EnumMap<>(OrderEvent.class));
TRANSITIONS.put(OrderStatus.CANCELLED, new EnumMap<>(OrderEvent.class));
```

Any attempt to apply an event to a terminal state fails immediately — the map lookup
returns null, the state machine throws `BizException`. A confirmed order cannot be
cancelled. A cancelled order cannot be paid. These constraints are enforced by data
structure, not by if-else conditions.

---

## Phase 2 Implications

In Phase 2, Kafka delivers payment confirmation events asynchronously.
The state machine integration point does not change:

```java
// Phase 1: called synchronously in OrderService
orderStateMachine.handleEvent(order, OrderEvent.PAYMENT_SUCCESS, "Payment confirmed");

// Phase 2: called from Kafka consumer
@KafkaListener(topics = "payment.confirmed")
public void onPaymentConfirmed(PaymentConfirmedEvent event) {
    Order order = orderRepository.findByOrderNo(event.getOrderNo());
    orderStateMachine.handleEvent(order, OrderEvent.PAYMENT_SUCCESS, "Payment gateway callback");
    orderRepository.save(order);
}
```

The state machine is indifferent to how the event arrived — HTTP request, Kafka message,
or scheduled job. The business rules are identical regardless of transport mechanism.

---

## Consequences

**Positive**:
- All legal transitions are visible in one place — zero ambiguity about what is allowed
- Illegal transitions are impossible by construction, not by convention
- Full audit trail of every transition, including the triggering event and reason
- State machine is transport-agnostic — Phase 2 Kafka integration requires no state machine changes

**Negative**:
- Two enums (`OrderStatus`, `OrderEvent`) must be kept synchronized
- Adding a new state requires updating both the enum and the TRANSITIONS map

**Accepted trade-off**: The discipline of maintaining two enums is a small price for
a system where order state bugs are impossible by construction rather than caught by
code review.
