# ADR-008: Transactionless Fast Path + DCL Cache Loading

**Date**: 2026-03  
**Status**: Accepted

---

## Decision

Moved Redis inventory deduction completely outside `@Transactional`. DB
connections are now acquired only after Redis confirms stock is available.
Cache miss loading uses local Double-Checked Locking (DCL) with
`ConcurrentHashMap` — not Caffeine, not Redisson.

## Impact

| Before | After |
|--------|-------|
| 420 DB connections needed (200 threads × 2 conns) | **50 connections** |
| Every request grabs a DB connection before talking to Redis | Rejected requests never touch the pool |
| 236 redundant DB reads on cache miss | **1 DB read** per cold start |
| p50: 11ms | **p50: 7ms** |

---

## What was wrong

Two issues, both discovered during pressure testing — not during design.

**Issue 1: The connection pool number should have been a red flag.**

`OrderService.doCreateOrder()` had `@Transactional`. The method called
`inventoryPort.deductStock()`, which executed a Redis Lua script — a network
call to a different system, while holding a DB connection. If Redis was slow
for any reason (network blip, large key scan, GC pause), that DB connection
just sat there waiting.

Worse: `RedisInventoryAdapter.deductStock()` used `Propagation.REQUIRES_NEW`,
which opened a *second* connection for the DB guard write. So every request
occupied 2 connections simultaneously. I needed a pool of 420 to avoid
deadlocking under 200-thread tests. I wrote a comment in `application-test.yml`
explaining why 420 was needed. In retrospect, the comment was documenting a
bug, not a design decision.

**Issue 2: Cache loading was a race condition I didn't notice until I read
the logs.**

When Redis has no inventory key (cold start, restart), `deduct_stock.lua`
returns `-1` and the Java code loads the value from DB into Redis. The first
version used `SET` — a plain overwrite. Under 200 concurrent threads, dozens
of them hit the miss simultaneously, all read the same DB value, and the last
`SET` overwrote deductions that earlier threads had already applied. Redis
ended up with a higher number than reality.

The DB guard write (`UPDATE ... WHERE available_stock >= qty`) prevented
overselling, so the tests passed. But Redis and DB were drifting, which is
exactly the kind of bug that doesn't show up until the reconciler logs start
complaining in production at 3am.

---

## How we fixed it

### Fix 1: Tear the transaction apart

Split `doCreateOrder()` into two pieces:

```
createOrder()                          ← no @Transactional
  ├─ checkRedisIdempotency()           ← Redis, 0 DB connections
  ├─ inventoryPort.deductStock()       ← Redis Lua, 0 DB connections
  └─ self.persistOrder()               ← @Transactional, 1 connection
       ├─ inventoryPort.persistDeduction()  ← DB guard write
       └─ orderRepository.save()            ← order insert, commit
```

`deductStock()` now returns a `DeductionResult` enum (`FAST_PATH` or
`DB_FALLBACK`) so the caller knows which compensation path to take if
`persistOrder()` fails. This replaces a ThreadLocal I briefly considered —
an enum return is explicit, stateless, and doesn't need lifecycle management.

The `InventoryPort` interface gained two methods: `persistDeduction()` (syncs
the Redis deduction to DB, joins the caller's transaction) and
`compensateDeduction()` (undoes `deductStock()` if the order save fails). In
Phase 3 when inventory becomes a remote service, these map to HTTP
confirm/cancel calls — the port abstraction holds.

Redis fallback path: when Redis is completely down, `deductStock()` catches the
exception and delegates to `InventoryAdapter` which still uses `REQUIRES_NEW`.
This is fine — the degraded path is slower and uses more connections, but it's
temporary and correct. The pool of 50 handles it because `REQUIRES_NEW`
connections are short-lived (one DB write, commit, release).

### Fix 2: Three iterations to get cache loading right

**v1: plain `SET`.** Shipped it, tests passed, read the logs, saw 50 threads
all printing "Cache miss — loaded from DB" at the same millisecond. Last writer
wins. Redis overstated stock. Caught by DB guard write, not by my code.

**v2: pre-check + `SETNX`.** Added a Redis `GET` before the DB read — if
another thread already loaded the key, skip the read. Replaced `SET` with
`SETNX` so only the first writer's value sticks. Write race: fixed. But the
logs showed 236 threads still read DB. The pre-check was useless because all
200 threads arrived before the first one's `SETNX` completed — they all saw
Redis empty, they all passed the check, they all hit DB.

Initially I wrapped `SETNX` in a Lua script (`load_if_absent.lua`), which was
pointless — `SETNX` is already a single atomic command. A reviewer pointed this
out. Switched to Spring Data Redis's native `setIfAbsent()` and deleted the
script.

**v3: DCL + `SETNX` (current).** Per-ticketTypeId lock via `ConcurrentHashMap`.
First check (lock-free): Redis `GET`. Second check (inside `synchronized`):
Redis `GET` again. Only the first thread that acquires the lock and still sees
an empty cache reads DB. Everyone else waits, wakes up, sees the cache
populated, and returns.

Result: 1 DB read per cold start per ticketTypeId. The other 199 threads never
touch DB.

**Why not Caffeine or Guava Striped for the lock pool?**

`cacheMissLocks` is a `ConcurrentHashMap<Long, Object>` that grows
monotonically — entries are never evicted. A reviewer flagged this as a memory
leak. The math: each entry is ~64 bytes (key boxing + node + Object). This is
a ticketing system — the total number of ticketTypeIds across all events is
bounded. 10,000 ticket types × 64 bytes = 640 KB. Introducing Caffeine's
eviction machinery or Guava's Striped lock pool to manage 640 KB is negative
ROI. If this were an e-commerce system with millions of SKUs, different answer.
Domain constraints matter.

---

## What we considered and rejected

**Distributed lock (Redisson) for cache loading.** Adds a network round-trip to
Redis for every cache miss, when a JVM-local lock does the same job without the
latency. The lock only needs to coordinate threads within the same process —
even in a multi-pod deployment, each pod loading the same key from DB once is
acceptable (they'll all load the same value, `SETNX` deduplicates the write).

**Removing `REQUIRES_NEW` from the fallback path.** Considered making the
fallback use the caller's transaction instead of its own. Problem: the
fallback's optimistic lock failure would roll back the caller's entire
transaction, including any work done before the inventory call. Keeping
`REQUIRES_NEW` isolates the failure. The cost (one extra connection in
fallback mode) is acceptable because fallback only triggers when Redis is down.

---

## Scale notes (not implemented, documented for interviews)

**Transactional Outbox.** The `AFTER_COMMIT` event listener has a crash window:
JVM dies after DB commit, before Kafka send. Message lost, inventory never
restored. Fix: outbox table + poller. Deferred — the crash window is ~2ms, and
the failure mode requires a JVM crash at that exact moment.

**Redis Cluster CROSSSLOT.** `release_stock_idempotent.lua` uses two keys with
different prefixes. In a cluster, they'd hash to different slots. Fix: Hash Tag
alignment (`{ticketType:42}` prefix). Noted in code comments. Not implemented
because we're on single-node Redis.

**Inventory sharding.** If a single ticketTypeId exceeds Redis single-thread
Lua capacity (~100k QPS), shard the key. Current benchmark: 361 QPS. Three
orders of magnitude away from needing this.

**Reconciler race condition.** The reconciler can read Redis and DB at different
points in time — if requests are between Redis deduction and DB commit, it may
"correct" Redis upward. DB guard write prevents overselling regardless. The
worst case is a user seeing available stock that's already reserved. Acceptable
at 5-minute reconciliation intervals.
