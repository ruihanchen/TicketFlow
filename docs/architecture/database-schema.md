# Database Schema

## Entity Relationship Diagram

```
┌─────────────────────┐
│        users        │
├─────────────────────┤
│ id          BIGSERIAL│ PK
│ username    VARCHAR │ UNIQUE
│ email       VARCHAR │ UNIQUE
│ password_hash VARCHAR│
│ role        VARCHAR │ USER | ADMIN
│ created_at  TIMESTAMP│
│ updated_at  TIMESTAMP│
└────────┬────────────┘
         │ 1
         │ user places orders
         │ N
         ▼
┌─────────────────────────────────────────────────────────┐
│                         orders                           │
├─────────────────────────────────────────────────────────┤
│ id              BIGSERIAL │ PK                           │
│ order_no        VARCHAR   │ UNIQUE — exposed to users    │
│ user_id         BIGINT    │ FK → users.id                │
│ ticket_type_id  BIGINT    │ FK → ticket_types.id         │
│ quantity        INT       │                              │
│ unit_price      DECIMAL   │ price snapshot at order time │
│ total_amount    DECIMAL   │ unit_price × quantity        │
│ status          VARCHAR   │ CREATED|PAYING|PAID|...      │
│ request_id      VARCHAR   │ UNIQUE — idempotency key     │
│ expired_at      TIMESTAMP │ CREATED_AT + 15 minutes      │
│ created_at      TIMESTAMP │                              │
│ updated_at      TIMESTAMP │                              │
└──────────┬──────────────────────────────────────────────┘
           │ 1
           │ order has many history records
           │ N
           ▼
┌──────────────────────────────┐
│     order_status_history     │
├──────────────────────────────┤
│ id          BIGSERIAL        │ PK
│ order_id    BIGINT           │ FK → orders.id
│ from_status VARCHAR          │ nullable (first transition)
│ to_status   VARCHAR          │
│ event       VARCHAR          │ INITIATE_PAYMENT | CANCEL_BY_USER | ...
│ reason      VARCHAR          │ human-readable context
│ created_at  TIMESTAMP        │
└──────────────────────────────┘


┌──────────────────────────────────────────┐
│                  events                  │
├──────────────────────────────────────────┤
│ id              BIGSERIAL │ PK           │
│ name            VARCHAR   │             │
│ description     TEXT      │             │
│ venue           VARCHAR   │             │
│ event_date      TIMESTAMP │             │
│ sale_start_time TIMESTAMP │             │
│ sale_end_time   TIMESTAMP │             │
│ status          VARCHAR   │ DRAFT|PUBLISHED|CANCELLED
│ created_at      TIMESTAMP │             │
│ updated_at      TIMESTAMP │             │
└──────────────┬───────────────────────────┘
               │ 1
               │ event has many ticket types
               │ N
               ▼
┌──────────────────────────────────────────┐
│             ticket_types                 │
├──────────────────────────────────────────┤
│ id          BIGSERIAL │ PK               │
│ event_id    BIGINT    │ FK → events.id   │
│ name        VARCHAR   │ e.g. "VIP Floor" │
│ price       DECIMAL   │ static — never   │
│ total_stock INT       │ changes after    │
│ created_at  TIMESTAMP │ creation         │
└──────────────┬───────────────────────────┘
               │ 1
               │ one inventory record per ticket type
               │ 1
               ▼
┌──────────────────────────────────────────┐
│               inventories                │
├──────────────────────────────────────────┤
│ id              BIGSERIAL │ PK           │
│ ticket_type_id  BIGINT    │ UNIQUE FK    │
│ total_stock     INT       │             │
│ available_stock INT       │ CHECK >= 0  │
│ version         INT       │ optimistic  │
│ updated_at      TIMESTAMP │ lock field  │
└──────────────────────────────────────────┘
```

---

## Table Design Decisions

### `users`

**Why `password_hash` not `password`?**

Passwords are never stored in plain text. BCrypt with cost factor 12 produces
a one-way hash. The column name makes this explicit — any developer reading the
schema knows this is not a reversible value.

**Why separate `role` column instead of a roles table?**

MVP has two roles: `USER` and `ADMIN`. A junction table (`user_roles`) is justified
when users can have multiple roles simultaneously. In TicketFlow, a user is either
a customer or an administrator — never both. A VARCHAR column with a CHECK constraint
is simpler and faster for this case.

---

### `events` and `ticket_types`

**Why are these two separate tables?**

An event is a container. A single Taylor Swift concert has multiple ticket categories:
VIP Floor ($2,888), Standard ($988), Economy ($488). Embedding ticket types inside
the events table would require either JSON columns (not queryable) or a fixed number
of ticket type columns (not flexible).

**Why is `price` on `ticket_types` and not on `orders`?**

`ticket_types.price` is the *listing price* — what we advertise.
`orders.unit_price` is the *transaction price* — what the user actually paid.
These must be separate. See the `orders` section below.

---

### `inventories` (separate from `ticket_types`)

**Why not just add `available_stock` to `ticket_types`?**

Two reasons:

1. **Access pattern divergence**: `ticket_types` is read-heavy and write-rarely
   (only admins change ticket names or prices). `inventories` is written on every
   single purchase. Combining them means every purchase write-locks a row that
   concurrent readers need for event browsing.

2. **Phase 2 isolation**: In Phase 2, inventory moves to Redis for atomic deduction.
   If inventory is embedded in `ticket_types`, the Redis integration bleeds into the
   Event domain. As a separate table, the entire inventory subsystem is self-contained.

**What is the `version` column?**

JPA optimistic locking. On every `UPDATE`, JPA adds `WHERE version = ?` and increments
`version` by 1. If two concurrent transactions read `version = 5` and both attempt
to update, only one succeeds. The other receives `OptimisticLockingFailureException`
and surfaces a retryable error to the user.

This prevents overselling without database row locks.

---

### `orders`

**Why `order_no` in addition to `id`?**

`id` is a sequential auto-increment integer. Exposing it to users reveals business
volume — a competitor can place two orders one week apart and calculate daily order
count from the difference.

`order_no` is generated as `TF{timestamp}{6-char-random}` — opaque, non-sequential,
safe to expose in URLs and receipts.

**Why `unit_price` snapshot instead of joining to `ticket_types.price`?**

Ticket prices can change after an order is placed. A user who paid $988 for a Standard
ticket must always see $988 on their receipt — regardless of what the ticket costs
today. `unit_price` is frozen at order creation time and never modified.

This is a deliberate denormalization for financial correctness.

**Why `request_id` with a UNIQUE constraint?**

`request_id` is the idempotency key supplied by the frontend (typically a UUID
generated before the purchase button is clicked). The UNIQUE constraint is the
last line of defense against duplicate orders:

- First line: application-level check (`orderRepository.existsByRequestId`)
- Last line: database UNIQUE constraint rejects the INSERT if the first line fails

Even if two concurrent requests both pass the application check simultaneously,
only one can successfully INSERT. The other receives a unique constraint violation,
which the application handles as a duplicate request.

**Why `expired_at` instead of computing expiry from `created_at`?**

Computing `created_at + 15 minutes` on every query requires the database to perform
arithmetic on every row scan. Storing `expired_at` explicitly allows a simple
index scan: `WHERE expired_at < NOW() AND status IN ('CREATED', 'PAYING')`.

The timeout cancellation job uses this index to find expired orders efficiently.

---

### `order_status_history`

**Why store history instead of just the current status?**

`orders.status` tells you where an order is now.
`order_status_history` tells you how it got there.

Production questions this table answers:
- "Why was order #TF123 cancelled?" → `event = PAYMENT_TIMEOUT`
- "Did the user try to pay before it expired?" → look for `event = INITIATE_PAYMENT`
- "How long does a typical order take from creation to confirmation?" → timestamp diff

Without this table, answering these questions requires log archaeology — searching
through potentially terabytes of application logs for specific order IDs.

**Why `event` column in addition to `from_status` and `to_status`?**

`PAYING → CANCELLED` can happen for three reasons:
- `PAYMENT_FAIL`: payment gateway rejected the charge
- `PAYMENT_TIMEOUT`: user didn't complete payment in time
- `CANCEL_BY_USER`: user explicitly cancelled

The `from_status` and `to_status` are identical for all three. The `event` column
preserves the business meaning of the transition.

---

## Indexes

```sql
-- Order queries (most frequent)
CREATE INDEX idx_orders_user_id     ON orders (user_id);
CREATE INDEX idx_orders_status      ON orders (status);
CREATE INDEX idx_orders_expired_at  ON orders (expired_at);

-- Timeout cancellation job
-- Scans: WHERE status IN ('CREATED', 'PAYING') AND expired_at < NOW()
-- Both status and expired_at indexes apply

-- Event and ticket browsing
CREATE INDEX idx_ticket_types_event_id          ON ticket_types (event_id);
CREATE INDEX idx_order_status_history_order_id  ON order_status_history (order_id);
```

**Why these indexes and not others?**

Indexes are not free. Every index adds write overhead on INSERT/UPDATE and storage cost.
Only indexes that correspond to known frequent query patterns are created.

The timeout cancellation job (`OrderTimeoutService`) runs every 60 seconds and queries:
```sql
SELECT * FROM orders
WHERE status IN ('CREATED', 'PAYING')
AND expired_at < NOW();
```

Without `idx_orders_expired_at`, this is a full table scan every minute — unacceptable
at scale. With the index, it is a range scan on a pre-sorted structure.

---

## Migration History

| Version | File | Description |
|---------|------|-------------|
| V1 | `V1__init_schema.sql` | Create all tables and indexes |
| V2 | `V2__add_event_to_order_status_history.sql` | Add `event` column to history table |

Managed by Flyway 10. Schema is never modified by Hibernate (`ddl-auto: validate`).
Every production schema change is a reviewed, versioned, irreversible migration script.
