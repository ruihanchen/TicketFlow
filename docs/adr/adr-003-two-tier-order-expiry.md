# ADR-003: Order Expiry — Two Independent Deadlines over Single Timeout

## Status

Accepted

## Context

A flash-sale order goes through two lifecycle phases with genuinely different time constraints. The cart hold reserves inventory for a user who's still browsing. Typically 10 to 15 minutes, long enough to compare options, short enough to keep tickets in circulation. The payment phase covers the gateway round-trip. 3 to 5 minutes, accounting for gateway processing, network latency, and 3DS verification.

The original design folded both into a single `expiredAt` field. The reaper used it to cancel stale CREATED orders and release inventory. `confirmPayment()` used it to reject late payment confirmations. Two consumers, one field, two meanings.

That coupling created a tuning conflict immediately. A 15-minute `expiredAt` also gave the payment gateway 15 minutes, so a user who abandoned checkout would hold inventory for the full window. A 5-minute `expiredAt` freed inventory faster but left almost no browsing time before the cart expired.

It also created a correctness gap at the boundary. A user who clicks Pay at minute 14 of a 15-minute window transitions to PAYING and starts the gateway round-trip. If the gateway takes 2 seconds, `confirmPayment()` arrives at minute 14:02, past `expiredAt`. Gateway charged the user. Application refused the confirmation. The field had no way to express "this user still has time to finish paying," because its only reference point was when the cart was created.

## Considered Options

### 1. Single timeout + fixed extension

When the user clicks Pay, extend `expiredAt` by a fixed amount (`expiredAt = expiredAt + 5 minutes`). One field, one column, reaper query unchanged.

### 2. Single timeout + dynamic reset

When the user clicks Pay, reset `expiredAt` to `now + 5 minutes`. Like Option 1 but anchored to current time instead of the original deadline.

### 3. Two independent deadlines

Add a second field, `paymentExpiredAt`, set once at CREATED → PAYING and never touched after. `payOrder()` checks `expiredAt` (cart deadline). `confirmPayment()` checks `paymentExpiredAt` (payment deadline). The reaper only looks at `expiredAt` on CREATED orders.

## Decision

Going with option 3. Two independent deadlines, implemented as `expiredAt` (cart) and `paymentExpiredAt` (payment) on the Order entity. V4 migration adds the column.

`paymentExpiredAt` is NULL until the user clicks Pay. At that point `startPaymentWindow()` sets it once, and a guard blocks re-calling it (which would shift the deadline forward and hand a slow client unlimited time).

### Invariants the model enforces

1. `paymentExpiredAt` is write-once. NULL before CREATED → PAYING, immutable after. Runtime guard in `startPaymentWindow()`, verified by `OrderStateMachineTest.start_payment_window_rejects_second_call`.
2. Reaper scope is bounded structurally, not by convention. `findExpiredCreatedForUpdate()` has `WHERE status = CREATED` hardcoded in JPQL. PAYING orders can't be reaped no matter what their cart deadline says.
3. The two deadlines are field-independent. Mutating one never changes the other. Verified by `OrderStateMachineTest.expire_payment_now_does_not_affect_is_cart_expired` and `expire_now_does_not_affect_payment_expired_at`.
### Why not single timeout + extension

Extending `expiredAt` keeps the coupling between cart hold and payment completion inside one field. The meaning of `expiredAt` becomes state-dependent. Before PAYING it's a cart deadline. After extension it's a payment deadline. Reading the value without knowing the order's current state tells you nothing about which phase that deadline actually belongs to.

Extending also overwrites the original cart deadline. You can't answer "when was this user's cart hold supposed to expire?" without reconstructing the extension history from commit timestamps. With two fields, both values are always present and unambiguous.

### Why not single timeout + reset

Resetting `expiredAt` to `now + 5min` has the same coupling as Option 1. One field, two meanings, state-dependent interpretation. Plus a distinct problem. The original cart hold deadline is permanently gone. With Option 1's fixed extension, you can at least subtract the extension amount to recover the original. With a reset, the original is lost entirely, which makes abandonment analysis and audit harder.

## Evidence

**V4 migration** ([`V4__add_order_payment_expired_at.sql`](../../db/migration/V4__add_order_payment_expired_at.sql)): adds `payment_expired_at TIMESTAMPTZ NULL` to the orders table. Postgres 16 handles nullable `ADD COLUMN` as metadata-only, so the ALTER grabs ACCESS EXCLUSIVE briefly on the catalog but doesn't rewrite rows.

**Guard separation in OrderService:**
- `payOrder()` calls `rejectIfCartExpired()`, which checks `expiredAt`.
- `confirmPayment()` calls `rejectIfPaymentExpired()`, which checks `paymentExpiredAt`.
- Neither method checks the other's deadline. The separation is structural, not something callers have to remember.
  **Reaper exclusion:** `findExpiredCreatedForUpdate()` has `WHERE status = CREATED` hardcoded in JPQL. A PAYING order with an expired cart deadline is invisible to the reaper regardless of its `expiredAt` value. `OrderTimeoutTest.paying_order_with_expired_cart_is_not_cancelled_by_reaper` verifies it.

**Core V4 invariant:** `ExpiredOrderRejectionTest.in_flight_payment_succeeds_even_when_cart_deadline_passed` sets `expiredAt` into the past while `paymentExpiredAt` is still in the future, then calls `confirmPayment()`. The order reaches PAID. This is the exact scenario that broke under the single-field design.

**Configuration:** `cart-window-minutes: 15` and `payment-window-minutes: 5` in `application.yml`, independently tunable. Changing one has no effect on the other.

## Consequences

Cart window and payment window are now independently tunable. Shortening the cart hold to improve ticket circulation doesn't change how long the payment gateway gets. The reaper also can't cancel an in-flight payment. PAYING orders are excluded from the reaper query structurally, and `confirmPayment()` only checks `paymentExpiredAt`. And because `paymentExpiredAt = NULL` until CREATED → PAYING, the lifecycle phase is visible directly in the data. `WHERE payment_expired_at IS NOT NULL AND status = 'PAYING'` gives you every order currently in the payment phase, without joining against status history.

The cost: two deadline fields and two guard methods (`isCartExpired`, `isPaymentExpired`) instead of one. A future contributor adding a new endpoint has to pick the right guard, and picking wrong silently uses the wrong deadline. The naming convention (`rejectIfCartExpired` vs `rejectIfPaymentExpired`) helps, but it's a convention, not a compile-time guarantee. PR review is the backstop for now. If the number of state-transition endpoints grows, an enum-based guard selector that ties each transition to its required deadline check would replace the convention.

`startPaymentWindow()` also has to be called exactly once, at CREATED → PAYING. The guard (`if paymentExpiredAt != null, throw`) enforces that at runtime, verified by `OrderStateMachineTest.start_payment_window_rejects_second_call`. A second call would shift the deadline forward and give a slow or malicious client more time than intended.

**Scope limitation:** the reaper enforces `expiredAt` (cart deadline) but doesn't touch PAYING orders at all. `paymentExpiredAt` is only enforced on the read path right now. `confirmPayment()` rejects a PAYING order whose payment window has passed, but no background process cancels that order or releases stock. In production, Stripe's `payment_intent.payment_failed` webhook would drive PAYING → CANCELLED. Without a payment gateway, user-initiated cancel is the only escape from PAYING state. A dedicated PAYING reaper is the fallback if webhook delivery turned out to be unreliable. This ADR documents where the gap is, not how to fill it.

## When We'd Revisit

The obvious thing that kills this design is a payment gateway that provides its own server-side expiry. Stripe's `PaymentIntent.expires_after` is exactly that, and once the gateway owns the timeout, `paymentExpiredAt` becomes dead weight. The app just handles the webhook.

A fully async payment flow (bank transfer, invoice, that sort of thing) would also break the "payment window" concept, because the user isn't sitting there waiting. The order would just park in PAYING until some callback eventually landed.

Beyond those, if a third phase ever showed up — say, a fulfillment window between PAID and ticket delivery — piling more nullable timestamp columns onto Order starts to smell. That's where I'd pull deadlines into their own entity keyed on `(order_id, phase, deadline)` and leave Order alone.

## Related

- V4 migration: [`V4__add_order_payment_expired_at.sql`](../../db/migration/V4__add_order_payment_expired_at.sql)
- Depends on: ADR-001 (the conditional UPDATE whose inventory the reaper releases).
- Tests: `ExpiredOrderRejectionTest` (6 tests covering both deadlines), `OrderTimeoutTest` (reaper behavior including PAYING exclusion).