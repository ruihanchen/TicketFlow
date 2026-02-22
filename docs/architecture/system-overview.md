# System Overview

## What TicketFlow Solves

Concert tickets for major artists sell out in seconds. The technical problem is not
"how do we sell tickets" — it is "how do we handle 50,000 simultaneous purchase
attempts for 1,000 available tickets without overselling, duplicating orders, or
locking up under load."

TicketFlow is built specifically around this constraint. Every architectural decision
traces back to one or more of these four guarantees:

| Guarantee | Mechanism |
|-----------|-----------|
| No overselling | Optimistic locking (Phase 1) → Redis Lua atomic ops (Phase 2) |
| No duplicate orders | requestId idempotency key + DB UNIQUE constraint |
| No stuck inventory | Order timeout with automatic cancellation and stock recovery |
| No illegal state | Event-driven state machine — invalid transitions structurally impossible |

---

## Architecture Evolution Strategy

TicketFlow is not designed for one phase. It is designed to evolve without rewriting
business logic.

```
Phase 1: MVP                Phase 2: High Concurrency       Phase 3: Microservices
─────────────────           ─────────────────────────       ────────────────────────
Single deployable           Single deployable +              Independent services
                            Redis + Kafka                    with own databases

┌─────────────┐             ┌─────────────┐                 ┌──────────────────┐
│  TicketFlow │             │  TicketFlow │    Kafka         │  inventory-svc   │
│     App     │             │     App     │◄──────────────── │  order-svc       │
│             │             │             │                  │  user-svc        │
│ PostgreSQL  │             │ PostgreSQL  │                  │  event-svc       │
└─────────────┘             │ Redis       │                  └──────────────────┘
                            └─────────────┘
```

**Key principle**: Domain boundaries are drawn in Phase 1 so that Phase 3 extraction
is a mechanical operation — move the package, add a datasource, swap the adapter.
Business logic does not change.

---

## Phase 1 Component Diagram

```
                    ┌─────────────────────────────────────────────────────────┐
                    │                  TicketFlow Application                  │
                    │                                                          │
 HTTP Request       │  ┌────────────────────────────────────────────────────┐ │
──────────────────► │  │              Spring Security Filter Chain           │ │
                    │  │                                                      │ │
                    │  │  JwtAuthenticationFilter                             │ │
                    │  │  └─ Validates Bearer token                          │ │
                    │  │  └─ Sets AuthenticatedUser in SecurityContext        │ │
                    │  └────────────────────────────────────────────────────┘ │
                    │                        │                                 │
                    │              ┌─────────▼──────────┐                     │
                    │              │    Controllers      │                     │
                    │              │                     │                     │
                    │              │  AuthController     │                     │
                    │              │  EventController    │                     │
                    │              │  OrderController    │                     │
                    │              └─────────┬──────────┘                     │
                    │                        │                                 │
                    │     ┌──────────────────┼──────────────────┐             │
                    │     │                  │                  │             │
                    │  ┌──▼──────────┐ ┌────▼──────┐ ┌────────▼────────┐   │
                    │  │ AuthService │ │EventService│ │  OrderService   │   │
                    │  └──────┬──────┘ └────┬───────┘ └──────┬──────────┘   │
                    │         │             │                 │              │
                    │         │             │     ┌───────────▼──────────┐  │
                    │         │             │     │   OrderStateMachine   │  │
                    │         │             │     │   (pure domain logic) │  │
                    │         │             │     └───────────┬──────────┘  │
                    │         │             │                 │              │
                    │         │             │     ┌───────────▼──────────┐  │
                    │         │             │     │    InventoryPort     │  │
                    │         │             │     │    (interface)       │  │
                    │         │             │     └───────────┬──────────┘  │
                    │         │             │                 │              │
                    │         │             │     ┌───────────▼──────────┐  │
                    │         │             │     │  InventoryAdapter    │  │
                    │         │             │     │  (local impl, Ph.1)  │  │
                    │         │             │     └───────────┬──────────┘  │
                    │         │             │                 │              │
                    │  ┌──────▼─────────────▼─────────────────▼──────────┐  │
                    │  │              Spring Data JPA / Hibernate          │  │
                    │  └──────────────────────────┬───────────────────────┘  │
                    │                              │                           │
                    └──────────────────────────────┼───────────────────────────┘
                                                   │
                                        ┌──────────▼──────────┐
                                        │    PostgreSQL 16     │
                                        │                      │
                                        │  users               │
                                        │  events              │
                                        │  ticket_types        │
                                        │  inventories         │
                                        │  orders              │
                                        │  order_status_history│
                                        └─────────────────────-┘
```

---

## Request Lifecycle: Place Order

This is the most complex flow in the system. Tracing it end-to-end shows how every
layer interacts.

```
Client                Controller        OrderService          InventoryPort
  │                       │                  │                     │
  │  POST /api/v1/orders  │                  │                     │
  │──────────────────────►│                  │                     │
  │                       │  createOrder()   │                     │
  │                       │─────────────────►│                     │
  │                       │                  │                     │
  │                       │                  │ 1. Check requestId  │
  │                       │                  │    idempotency      │
  │                       │                  │                     │
  │                       │                  │ 2. Load TicketType  │
  │                       │                  │    validate event   │
  │                       │                  │    isOnSale()       │
  │                       │                  │                     │
  │                       │                  │ 3. Check stock      │
  │                       │                  │    (pre-flight)     │
  │                       │                  │                     │
  │                       │                  │  deductStock()      │
  │                       │                  │────────────────────►│
  │                       │                  │                     │ 4. Load Inventory
  │                       │                  │                     │    version=N
  │                       │                  │                     │
  │                       │                  │                     │ 5. UPDATE WHERE
  │                       │                  │                     │    version=N
  │                       │                  │                     │    (optimistic lock)
  │                       │                  │◄────────────────────│
  │                       │                  │                     │
  │                       │                  │ 6. Order.create()   │
  │                       │                  │    orderRepo.save() │
  │                       │                  │    @Transactional   │
  │                       │                  │                     │
  │◄──────────────────────│◄─────────────────│                     │
  │  201 order{CREATED}   │                  │                     │
  │                       │                  │                     │
  │                       │         [If step 6 throws]             │
  │                       │                  │  releaseStock()     │
  │                       │                  │────────────────────►│
  │                       │                  │  (SAGA compensation)│
```

**Why is `releaseStock` called on failure instead of relying on `@Transactional` rollback?**

`@Transactional` rolls back the database transaction, but only for operations within
the same transaction. In Phase 2, `deductStock` calls a remote Inventory Service over
HTTP — that operation is already committed in a separate service. `@Transactional`
cannot reach across a network boundary.

By writing manual compensation today, the Phase 2 migration adds distributed
compensation without restructuring the business logic.

---

## Order State Machine

```
                    ┌─────────────┐
         ┌──────────│   CREATED   │──────────┐
         │          └──────┬──────┘          │
  CANCEL_BY_USER           │           SYSTEM_TIMEOUT
         │          INITIATE_PAYMENT          │
         │                 │                 │
         ▼                 ▼                 ▼
  ┌────────────┐    ┌─────────────┐   ┌────────────┐
  │ CANCELLED  │    │   PAYING    │   │ CANCELLED  │
  │ (terminal) │    └──────┬──────┘   │ (terminal) │
  └────────────┘           │          └────────────┘
                    ┌──────┴──────────────────┐
                    │             │            │
             PAYMENT_SUCCESS  PAYMENT_FAIL  PAYMENT_TIMEOUT
                    │             │            │
                    ▼             ▼            ▼
              ┌──────────┐ ┌──────────┐ ┌──────────┐
              │   PAID   │ │CANCELLED │ │CANCELLED │
              └────┬─────┘ │(terminal)│ │(terminal)│
                   │        └──────────┘ └──────────┘
            CONFIRM_TICKET
                   │
                   ▼
           ┌──────────────┐
           │  CONFIRMED   │
           │  (terminal)  │
           └──────────────┘
```

Every arrow is an `OrderEvent`. Every node is an `OrderStatus`.
The `OrderStateMachine` maps `(current status, event) → next status`.
Any combination not in the map is an illegal transition and throws immediately.

---

## Security Model

```
Public endpoints (no token required):
  POST /api/v1/auth/register
  POST /api/v1/auth/login
  GET  /api/v1/events/**

Authenticated endpoints (valid JWT required):
  POST /api/v1/orders
  POST /api/v1/orders/{orderNo}/cancel
  POST /api/v1/orders/{orderNo}/pay
  POST /api/v1/orders/{orderNo}/confirm-payment
  GET  /api/v1/orders/**

Admin-only endpoints (valid JWT + role=ADMIN required):
  POST /api/v1/admin/events
  PUT  /api/v1/admin/events/{id}/publish
  PUT  /api/v1/admin/events/{id}/cancel
```

**JWT structure:**
```json
{
  "sub": "1",
  "username": "testuser",
  "role": "USER",
  "iat": 1773357570,
  "exp": 1773443970
}
```

The JWT carries `userId`, `username`, and `role`. Each authenticated request
requires zero database queries to identify the user — all identity information
is in the token. Database is only queried when business data is needed.

**Why `sub` is userId, not username?**

Usernames can theoretically change. User IDs never change. Foreign keys in `orders`
reference `users.id`. Using `userId` as the JWT subject means the token's identity
claim is consistent with the database schema.

---

## Data Flow: Timeout Cancellation

```
┌─────────────────────────────────────────────────────┐
│              OrderTimeoutService                     │
│         @Scheduled(fixedDelay = 60_000ms)            │
│                                                      │
│  Every 60 seconds:                                   │
│                                                      │
│  SELECT * FROM orders                                │
│  WHERE status IN ('CREATED', 'PAYING')               │
│  AND expired_at < NOW()                              │
│                                                      │
│  For each expired order:                             │
│  ┌────────────────────────────────────────────────┐  │
│  │ try {                                          │  │
│  │   stateMachine.handleEvent(SYSTEM_TIMEOUT)     │  │
│  │   inventoryPort.releaseStock(...)              │  │
│  │   orderRepository.save(order)                  │  │
│  │ } catch (Exception e) {                        │  │
│  │   log.error(...) // skip, process next order   │  │
│  │ }                                              │  │
│  └────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────┘
```

**Why per-order try/catch?**

If one order fails to cancel (e.g. a DB constraint violation caused by a concurrent
user cancellation), the job must continue processing the remaining expired orders.
Without per-order error isolation, one bad order poisons the entire batch.

**Phase 2 replacement:**
`@Scheduled` polling is replaced with a Redis delayed queue. When an order is created,
a message is pushed to Redis with a TTL equal to the expiry window. When the TTL
expires, Redis delivers the message to a consumer that executes the same cancellation
logic. This eliminates the 60-second polling lag and removes the database scan entirely.

---

## Technology Decisions Summary

| Concern | Technology | Alternative Considered | Why This Choice |
|---------|-----------|----------------------|-----------------|
| Language | Java 21 | Java 17, Kotlin | LTS, virtual threads ready for Phase 2 |
| Framework | Spring Boot 3.3.5 | Quarkus, Micronaut | Ecosystem maturity, team familiarity |
| ORM | JPA + Hibernate 6 | JOOQ, MyBatis | Native optimistic locking support |
| Auth | JWT (stateless) | Session-based | Stateless; scales horizontally without sticky sessions |
| Migration | Flyway | Liquibase | Simpler SQL-first approach |
| Concurrency | Optimistic locking | Pessimistic locking, Redis | No row locks; Phase 2 Redis migration is a single adapter swap |
| Pool | HikariCP | DBCP2 | Industry standard; fastest connection pool for PostgreSQL |
