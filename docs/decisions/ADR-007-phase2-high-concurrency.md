# ADR-007: Phase 2 — Redis Lua Inventory, Kafka Async Cancellation

**Date**: 2026-03
**Status**: Accepted

---

## Context

Phase 1 shipped with three known issues, all measured:

**Inventory throughput.** k6 Phase 1 baseline (40 VUs, 60s): 16,976 lock
conflicts, 15.59% success rate. `ConcurrentInventoryTest` (200 threads, 50
tickets): ~33 seconds to clear. The system was correct — zero overselling — but
84% of request capacity evaporated on retry collisions.

**Cancellation coupling.** `cancelOrder()` called `inventoryPort.releaseStock()`
synchronously. Order and Inventory were entangled in the same transaction. Slow
inventory = slow cancel API. A DB error in restoration failed the entire cancel.

**Timeout precision.** `OrderTimeoutService` polled every 60 seconds with an
unbounded `SELECT`. Up to 59s cancellation lag. On restart after an outage,
loading thousands of expired orders into a `List` was a direct OOM path.

---

## Decision 1: Redis Lua for Inventory Deduction

### What was tried and rejected

We looked at pessimistic locking (`SELECT FOR UPDATE`) first. At 200+ threads it
immediately choked the HikariCP pool — the entire app, not just the inventory
endpoint, started timing out. Threads piled up waiting for lock release,
connection slots filled, and the cascading timeouts spread to unrelated endpoints.
Not a viable path at flash-sale concurrency.

Optimistic locking (Phase 1) avoids row locks but creates a retry storm instead.
Measured: 16,976 conflicts in 60 seconds. More retries → more DB load → longer
commits → larger retry window. Self-reinforcing, and we saw it in the logs.

### What we did

Single Lua script, `deduct_stock.lua`. Redis is single-threaded — GET + DECRBY
inside Lua is atomic. The race condition that generates retries doesn't exist.

Three layers:
1. Redis Lua — hot path, atomic
2. DB guard write — `UPDATE ... WHERE available_stock >= qty`
3. DB CHECK constraint — `available_stock >= 0`, last resort

### Numbers

| | Phase 1 | Phase 2 |
|--|---------|---------|
| Success rate | 15.59% | **99.97%** |
| Lock conflicts | 16,976 | **0** |
| QPS | 331.5 | 340.5 |
| p99 | 30ms | 36ms |

QPS didn't change — the machine's ceiling was never the issue.

### Redis as a soft dependency

`RedisInventoryAdapter` catches all `RedisException` and falls back to
`InventoryAdapter`. The system degrades to Phase 1 behavior, not a crash.

k6 fallback test (Redis container stopped): `success_rate=35.11%,
error_rate=0.00%`. `RedisDegradationTest` (200 threads, Redis mocked to throw):
`sold=50, overselling=0`.

---

## Decision 2: Kafka for Async Cancellation

### What changed

Replaced `inventoryPort.releaseStock()` inside `cancelOrder()` with
`ApplicationEventPublisher.publishEvent(OrderCancelledEvent)`. Event forwarded
to Kafka via `@TransactionalEventListener(AFTER_COMMIT)`.

### Why AFTER_COMMIT is non-negotiable

Publishing inside `@Transactional` sends the Kafka message before the DB
commits. If the DB then rolls back — network blip, constraint violation,
anything — the consumer restores inventory for an order that was never
cancelled. Ghost restore. Inventory silently overstated.

`AFTER_COMMIT` ensures the DB write is durable before the message is sent.

### Known gap: JVM crash window

If the process dies between DB commit and Kafka send, the message is lost.
Reconciler can't recover this — it compares `redis.available_stock` vs
`db.available_stock`, and after the crash both show the same pre-cancellation
value. No drift, no action. Ticket is permanently under-sold.

The fix is the Transactional Outbox: write to an `outbox_events` table in the
same DB transaction, relay process forwards to Kafka. Deferred to Phase 3.

**Why deferred**: cancellation events are ~5% of total request volume (observed
in k6 runs). For that fraction, the JVM crash window between commit and Kafka
send is very low probability. The `FATAL_KAFKA_DROP` log pattern covers manual
recovery for the rare cases it fires. Outbox adds a DB table, a relay process,
and write amplification on every cancel. That's not worth it at this scale.

### OOM defense

`@Async("kafkaEventExecutor")` moves Kafka I/O off the committing thread. The
executor is bounded: `queueCapacity=1000`, `CallerRunsPolicy`. Spring Boot's
default executor uses an unbounded queue — during a Kafka outage, events
accumulate without limit. At ~200 bytes/event, that's OOM territory.
`CallerRunsPolicy` applies backpressure instead: the submitting thread runs the
task itself when the queue fills.

---

## Decision 3: Atomic Consumer Idempotency

### The race condition

Kafka at-least-once + consumer rebalance = same message reaching two instances
concurrently. Naïve pattern:

```
if NOT exists(messageId):   ← both consumers pass
    INCRBY inventoryKey qty ← both increment
    SET messageId done
```

Result: double-restore. Inventory overstated.

### What we did

`release_stock_idempotent.lua` — SETNX + INCRBY + SETEX as one atomic Redis
command. Only one consumer wins the SETNX. The other gets `DUPLICATE`.

Three return paths:

`OK` — first delivery. Redis incremented, messageId recorded. Update DB. ACK.

`DUPLICATE` — messageId already in Redis. Two scenarios, indistinguishable
without extra state: (a) previous attempt succeeded end-to-end, ACK failed; or
(b) previous attempt: Redis OK, DB failed. We skip the DB call. If (a), calling
DB again double-restores — that's an oversell risk. If (b), Reconciler detects
Redis > DB drift and fixes it within 5 minutes. We accept the brief under-sell
over the oversell risk.

`CACHE_MISS` — inventory key gone (Redis restart, eviction). Can't INCRBY a
non-existent key — that would fabricate a stale value. Update DB directly.
Reconciler rebuilds Redis on next cycle.

### The +2 bug

Found during integration testing. Original `OK` branch called
`inventoryPort.releaseStock()`, which internally does Redis INCRBY + DB update.
But the Lua script had already done the INCRBY. Inventory went up by 2 per
cancellation.

Fix: added `releaseStockDbOnly()` to `InventoryPort`. After Lua runs, consumer
only updates DB. Lua owns Redis; Java owns DB. Enforced at the interface level,
not by convention.

Test: `KafkaInventoryRestoreTest.sameMessageId_deliveredTwice` —
`afterFirst=10, afterDuplicate=10`. No double-restore.

### Why not a `consumed_messages` table?

A DB idempotency table eliminates the DUPLICATE ambiguity: wrap the inventory
update and the idempotency insert in one ACID transaction. The correct solution
for payment systems.

Cost here: one extra DB write per message, periodic cleanup of expired rows,
extra transaction boundary. At current scale, Lua + Reconciler covers the
invariants with less overhead. `consumed_messages` is the documented Phase 3
path.

### messageId TTL: 24 hours

A message older than 24h won't be redelivered under normal conditions. 24h
bounds Redis memory for the idempotency keys (~50 bytes each) without
meaningfully opening the duplicate window.

---

## Decision 4: OrderTimeoutService Batch Limiting

Changed `findExpiredOrders()` from unbounded `SELECT` to
`PageRequest.of(0, BATCH_SIZE)` where `BATCH_SIZE=100`.

Without the limit: after a system outage, 10,000+ orders expire simultaneously.
The query loads the entire result set into heap before processing starts. OOM.

At 100 orders/cycle × 60s polling interval: 6,000 cancellations/minute capacity
on a single node. Sufficient for current load.

---

## Open Items

| Issue | Trigger | Mitigation | Fix |
|-------|---------|-----------|-----|
| JVM crash inventory leak | Process dies between commit + Kafka send | `FATAL_KAFKA_DROP` log | Transactional Outbox (Phase 3) |
| DUPLICATE DB drift | Previous DB write failed on first delivery | Reconciler corrects within 5 min | `consumed_messages` table (Phase 3) |
| Timeout polling lag | Order expires mid-cycle | Max 59s delay | Redis ZSET delayed queue (Phase 3) |
| No distributed tracing | Multi-service incident | `orderNo` + `messageId` in all logs | MDC + Micrometer (Phase 3) |

---

## References

`RedisInventoryAdapter`, `RedisInventoryManager`, `deduct_stock.lua`,
`release_stock.lua`, `release_stock_idempotent.lua`, `OrderCancelledConsumer`,
`AsyncConfig`, `KafkaConsumerConfig`, `InventoryReconciliationJob`,
`ConcurrentInventoryTest`, `KafkaInventoryRestoreTest`, `RedisDegradationTest`,
`OrderTimeoutTest`, [benchmark results](../benchmarks/mvp-load-test-results.md),
ADR-004, ADR-006.
