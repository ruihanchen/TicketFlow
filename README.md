# TicketFlow

Built to solve the data consistency challenges in flash-sale scenarios, where DB
contention usually kills performance. Phase 1 established the data model and
business logic. Phase 2 moved inventory deduction to Redis and order cancellation
to Kafka to fix three specific bottlenecks measured in Phase 1.

**Stack**: Java 21 · Spring Boot 3.3.5 · PostgreSQL · Redis 7 · Kafka (KRaft) · Testcontainers

---

## What Changed Between Phases

Phase 1 ran all inventory writes through PostgreSQL optimistic locking. Under
load it worked correctly — zero overselling — but 84% of requests wasted on
lock-conflict retries.

k6, 40 VUs, 60s, identical load profile:

| | Phase 1 | Phase 2 | Fallback (Redis DOWN) |
|--|---------|---------|----------------------|
| Success rate | **15.59%** | **99.97%** | 35.11% |
| Lock conflicts | 16,976 | **0** | 10,492 |
| QPS | 331.5 | 340.5 | 266.2 |
| p99 | 30ms | 36ms | 148ms |
| Error rate | 0.00% | 0.00% | **0.00%** |

QPS is nearly the same across phases. The machine wasn't the bottleneck — the
retry collisions were. Phase 2 gets rid of this bottleneck by moving the hot path to Redis. The fallback column matters: with
Redis fully down, the system degrades to Phase 1 behavior, not a crash.

Full numbers: [docs/benchmarks/mvp-load-test-results.md](docs/benchmarks/mvp-load-test-results.md)

---

## The Three Fixes

**Fix 1: Inventory deduction**

Replaced optimistic locking with a Redis Lua script (`deduct_stock.lua`). Redis
is single-threaded, so the check-and-decrement is atomic by construction — no
retry loop needed. DB guard write keeps PostgreSQL accurate. Reconciler corrects
Redis/DB drift.

Integration test: 200 threads, 50 tickets. Phase 1: ~33s, heavy WARN logs.
Phase 2: under 3s, zero lock-conflict logs, zero overselling.

**Fix 2: Cancellation path**

`cancelOrder()` used to call `inventoryPort.releaseStock()` synchronously,
coupling Order and Inventory in the same transaction. Changed to publishing an
`OrderCancelledEvent` via `@TransactionalEventListener(AFTER_COMMIT)`. Consumer
restores inventory asynchronously.

The `AFTER_COMMIT` part is non-negotiable. Publishing inside the transaction
means a DB rollback sends a ghost restore to Kafka — inventory goes up for an
order that was never actually cancelled.

**Fix 3: Consumer idempotency**

Kafka is at-least-once. During rebalance, two consumer instances can receive the
same message. A naïve `if not exists → increment` is a race condition — both
pass the check before either sets the key. Fixed with `release_stock_idempotent.lua`:
SETNX + INCRBY + SETEX as one atomic Redis command. Only one consumer wins.

One bug hit during implementation: the `OK` branch originally called
`inventoryPort.releaseStock()`, which itself does a Redis INCRBY. The Lua script
had already done the INCRBY. Result: inventory incremented twice. Fixed by
adding `releaseStockDbOnly()` to `InventoryPort` — after Lua runs, the consumer
only touches DB. Lua owns Redis; Java owns DB.

---

## Architecture

```
HTTP ──► OrderService ──── InventoryPort ──────────► Redis (Lua)
              │             (Port & Adapter)               │
              │                                            ▼
              │  publishEvent() [in-transaction]       PostgreSQL
              ▼
     OrderCancelledKafkaPublisher
              │  (@TransactionalEventListener AFTER_COMMIT, @Async)
              ▼
           Kafka
              │
              ▼
     OrderCancelledConsumer
       Lua idempotency · releaseStockDbOnly · DLQ on exhaustion
```

`InventoryPort` lives in the Order domain. `OrderService` has zero imports from
the inventory package. Swapping to a remote HTTP adapter in Phase 3 touches one
file. See ADR-003.

---

## Test Coverage

All integration tests run against real containers (Testcontainers). No H2, no
mocks for infrastructure.

| What's tested | Class | Result |
|---------------|-------|--------|
| Zero overselling, Redis active | `ConcurrentInventoryTest` | sold=50, oversold=0 ✅ |
| Zero overselling, Redis down | `RedisDegradationTest` | sold=50, oversold=0 ✅ |
| Duplicate request handling | `IdempotencyTest` | 1 order created, 0 unexpected errors ✅ |
| Async restore — user cancel | `KafkaInventoryRestoreTest` | stock restored ✅ |
| Async restore — system timeout | `KafkaInventoryRestoreTest` | stock restored ✅ |
| Kafka redelivery — no double-restore | `KafkaInventoryRestoreTest` | afterDuplicate = afterFirst ✅ |
| Timeout idempotency | `OrderTimeoutTest` | second run publishes 0 events ✅ |

12/12 pass.

---

## Known Gaps

**JVM crash window**: if the process dies between DB commit and Kafka send, the
cancellation message is lost and the inventory never comes back. Reconciler can't
detect it — both Redis and DB show the same pre-cancellation count. `FATAL_KAFKA_DROP`
log exists for manual recovery. Fix is the Transactional Outbox (Phase 3).

**Timeout polling lag**: up to 59 seconds between order expiry and cancellation.
Fix is a Redis ZSET delayed queue (Phase 3).

**No distributed tracing**: incidents require log grep by `orderNo` or
`messageId`. MDC + Micrometer are Phase 3.

---

## Running It

```bash
# Prerequisites: Java 21, Docker

cd docker && docker-compose up -d
./mvnw spring-boot:run -pl app
./mvnw test   # runs all 12 integration tests
```

---

## Decisions

- [ADR-001: Multi-Module Maven](docs/decisions/ADR-001-multi-module-structure.md)
- [ADR-002: Inventory as Separate Domain](docs/decisions/ADR-002-inventory-as-separate-domain.md)
- [ADR-003: Port & Adapter](docs/decisions/ADR-003-port-adapter-pattern.md)
- [ADR-004: Optimistic Locking (Phase 1)](docs/decisions/ADR-004-optimistic-locking-mvp.md)
- [ADR-005: Order State Machine](docs/decisions/ADR-005-event-driven-state-machine.md)
- [ADR-006: Phase 1 Idempotency](docs/decisions/ADR-006-idempotency-phase1-jpa-limitation.md)
- [ADR-007: Phase 2 High-Concurrency](docs/decisions/ADR-007-phase2-high-concurrency.md)

## Roadmap

**Phase 1 ✅** — JWT auth, event/ticket CRUD, optimistic locking, state machine,
timeout polling, Port & Adapter, Flyway.

**Phase 2 ✅** — Redis Lua inventory, Redis SETNX idempotency, Kafka async
cancellation, consumer idempotency, Redis fallback, bounded executor, graceful
shutdown.

**Phase 3** — Transactional Outbox, `consumed_messages` table, Redis ZSET timeout
queue, MDC tracing, microservice extraction.
