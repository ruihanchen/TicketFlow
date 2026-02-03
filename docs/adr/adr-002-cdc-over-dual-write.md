# ADR-002: Cache Consistency — CDC over Dual-Write

## Status

Accepted. Supersedes the Redis Lua dual-write design documented in [`redis-lua-archived.md`](../benchmarks/results/redis-lua-archived.md).

## Context

After replacing `@Version` with a conditional UPDATE (ADR-001), the write path had zero conflicts and zero oversell. What it didn't have was throughput. Once stock ran out, every subsequent request still hit the database. Under 200 VUs with 500 tickets, 36,823 sold-out requests queued behind the Postgres row lock to get a 409 back.

My first attempt at fixing that was a Redis Lua dual-write. The write path ran a Lua script in Redis (atomic check-and-decrement) and then wrote through to Postgres via the conditional UPDATE. Once Redis stock hit zero, sold-out checks resolved in Redis and never opened a database connection. Throughput went from 1,226 to 2,903 req/s. P95 dropped from 280ms to 79ms. Numbers looked right.

The problem showed up during an observability pass. I was adding Micrometer counters to the inventory write path, tracing from `deductStock()` through the Redis DECRBY and the Postgres conditional UPDATE. Halfway through, I realized the two operations shared no transaction boundary. If the JVM died between them (OOM kill, container eviction, anything), the compensation code in `safeReleaseStock()` would never run. It lived in a catch block inside the same process that just died. Redis would show one fewer ticket than Postgres. Permanently. No code path anywhere could detect or fix the gap.

At first glance the failure looks reconcilable. The crash always produces the same direction: Redis shows fewer tickets than Postgres, because Redis decremented and Postgres didn't. A scheduled job could detect that and resync Redis upward. Except the reconciliation races with in-flight dual-writes. Between when the job reads Postgres and when it writes to Redis, new decrements land in both stores. The reconciliation overwrites those in Redis, pushing Redis above Postgres. Now Redis shows more stock than actually exists, and a user who checks Redis attempts a purchase the database is going to reject. Reconciliation meant to fix undercounting ends up creating overcounting. Overcounting is the direction you can't safely reverse under load.

## Considered Options

### 1. Patch the compensation logic

Keep the dual-write as the primary path. Add a scheduled job that periodically compares Redis to Postgres and resyncs. The crash failure mode (Redis < Postgres) looks reconcilable in isolation, but under concurrent load the reconciliation itself can overshoot and flip the direction (Redis > Postgres). See Context above.

### 2. Outbox pattern

Inside the same Postgres transaction that updates `inventories`, write a row to an outbox table describing the change. A separate process (polling, or CDC on the outbox table) consumes those rows and updates Redis. The outbox decouples the event schema from the table schema, so you can evolve published events independently of the `inventories` layout.

### 3. Cache-aside (invalidate on write)

Delete the Redis key on every inventory write. The next read misses Redis, falls through to Postgres, and repopulates the cache.

### 4. CDC via WAL tailing

Postgres is the only inventory writer. Debezium tails the write-ahead log and pushes every committed change to Redis. Redis becomes a read-only projection of Postgres state.

## Decision

Going with option 4. CDC via embedded Debezium. Postgres is the sole inventory writer, and Redis is populated exclusively by `InventoryChangeHandler`, which consumes WAL events off the Debezium engine.

Redis state follows committed Postgres state. A committed write reaches Redis after CDC propagation (sub-second in benchmarks). An uncommitted write never reaches Redis, because the application never writes to Redis directly. One caveat: if the CDC handler fails to process a specific event, that event is lost. See Consequences for how I traded that off.

### Why not patch the compensation

A reconciliation job fixes the symptom (diverged values) but not the cause (two writers to two stores with no atomic boundary). Under concurrent load, a reconciliation that resyncs Redis from Postgres races with in-flight dual-writes. Reconciling correctly under concurrency means locking both stores simultaneously, which is the distributed coordination problem we're trying to avoid in the first place.

### Why not outbox

Outbox is for reliably getting events out to other services from inside a DB transaction. You write the event row in the same tx as the business write, so either both commit or neither does, no phantom events to chase down afterward. The reason it's worth the extra write is schema decoupling: you publish something like `StockDecreased` with its own stable shape, independent of whatever `inventories` happens to look like internally.

We don't have any of that going on. The only CDC consumer is our own Redis cache, it already reads `available_stock` straight out of the WAL payload, and there's no external contract anyone has to maintain. One extra INSERT on every write for decoupling we'd never use. If TicketFlow ever grew external subscribers that needed a stable event shape, that's the point where outbox would start making sense again.

### Why not cache-aside

Deleting the Redis key on every write creates a window where Redis has no value at all, and the next read falls through to Postgres. Under flash-sale traffic, that window means a burst of database reads right when the write path is under peak contention. CDC keeps Redis populated continuously, so the read path only falls through when Redis itself is unreachable.

## Evidence

**Dual-write benchmark** (200 VUs, 500 tickets, 30s):

| Metric | Dual-write (Redis Lua) | DB-only (Redis stopped) |
|---|---|---|
| Throughput | 2,903 req/s | 1,226 req/s |
| Blended p95 | 79ms | 280ms |
| Sold-out hitting DB | ~0 (resolved in Redis) | 36,823 |
| Oversell | 0 | 0 |

Full data: [`redis-lua-archived.md`](../benchmarks/results/redis-lua-archived.md), [`redis-down-fallback-archived.md`](../benchmarks/results/redis-down-fallback-archived.md)

**CDC benchmark: realistic flash sale** (200 VUs, 500 tickets, 30s):

| Metric | With CDC read cache | Without (DB-only baseline) |
|---|---|---|
| Write-path requests | 1,332 | 37,329 |
| Sold-out hitting DB | 832 | 36,823 |
| Short-circuited at Redis | 947,420 (99.85%) | 0 |
| Orders sold | 500 | 500 |

Like the dual-write, CDC resolves sold-out checks in Redis without touching the database. Unlike the dual-write, there's only one writer, so divergence isn't possible.

**CDC benchmark: spike correctness** (ramping-arrival-rate, 150 RPS peak, 3 min):

| Metric | Value |
|---|---|
| Oversell audit | PASS (500 sold + 0 remaining = 500) |
| Sold-out latency p95 / p99 | 5.45ms / 8.22ms |
| Write latency p95 | 16.6ms |

**Test coverage:**

- `InventoryCdcSyncTest`: write to Postgres, poll Redis until the CDC-propagated value appears. End-to-end proof the pipeline works in Testcontainers.
- `InventoryQueryServiceFallthroughTest`: when Redis returns null, the read path falls through to Postgres and records a `cache_miss` metric.
- `cdc-crash-convergence.js`: k6 sends continuous write traffic. Partway through, the operator force-kills the Java process. After restart, Redis converges to Postgres state with zero permanent divergence. Full procedure, store snapshots, and screenshots in [`cdc-read-cache.md`](../benchmarks/results/cdc-read-cache.md#crash-convergence).
## Consequences

The big win is that a single writer rules out an entire class of divergence failures. No code path anywhere can leave Redis and Postgres disagreeing on a completed write, because application code never writes to Redis in the first place. `RedisInventoryManager.getStock()` is a pure read. No SET, no DECRBY on inventory keys from anywhere in the app. Only `InventoryChangeHandler` (the CDC consumer) ever writes. And the read path degrades gracefully. If Redis is down, `InventoryQueryService` catches the exception, records a `cache_fallthrough` metric, and loads from Postgres. The write path doesn't care, because it never touched Redis to begin with.

Now the costs.

CDC adds propagation lag. Around 0.5s on average, measured by `ticketflow_cdc_lag_seconds`. A client reading stock right after the last ticket sells might see stock > 0, try to buy, and get a 409. Under 200 concurrent VUs, that lag window produced 832 sold-out responses. UX cost, not a correctness cost. The write path catches every one of them.

The CDC handler swallows all exceptions to keep the Debezium engine thread alive. If a specific event fails to process (Redis write timeout, JSON parse error), the event's offset still gets committed, and the event won't be replayed. That inventory row stays stale in Redis until the next Postgres write triggers a new WAL event. `ticketflow_cdc_handler_errors_total` fires when this happens, but there's no automatic retry. I made that trade on purpose. A dead engine thread stops the entire pipeline and stales every row. A swallowed exception stales just one row, and only temporarily.

Embedded Debezium also creates a health visibility gap that Spring Boot's default checks don't cover. I closed it with a custom `DebeziumHealthIndicator`. See the implementation note below.

One more thing. Postgres has to run with `wal_level=logical` and a replication slot. The slot retains WAL segments until Debezium acknowledges them, so if the CDC consumer is down for an extended period, unacknowledged WAL piles up on disk. In production, set `max_slot_wal_keep_size` to cap retention, and monitor `ticketflow_cdc_lag_seconds` as early warning.

## Implementation Note: CDC Deployment

TicketFlow has exactly one CDC consumer (inventory to Redis). Kafka Connect would need a Kafka cluster, a Connect worker, and connector configuration, all for fan-out and multi-consumer patterns this project doesn't use. Embedded Debezium runs the engine inside the application's JVM, reads from the same DataSource Spring already manages, and writes to Redis through `InventoryChangeHandler`.

Downside: the engine runs in a daemon thread with no external process manager. If that thread dies (replication slot lost, WAL reclaimed, internal parser error), Spring Boot's built-in health checks can't see it. `DebeziumHealthIndicator` closes that gap by reading a volatile flag set by Debezium's `CompletionCallback`. The callback fires when the engine's `run()` method exits for any reason, flipping the flag to false so the next `/actuator/health` poll reports DOWN. `ticketflow_cdc_handler_errors_total` and `ticketflow_cdc_lag_seconds` cover the less catastrophic failures (individual event processing errors, pipeline slowdown).

## When We'd Revisit

A few things would push us off embedded Debezium.

The big one is scaling out the app horizontally. Postgres logical replication slots allow one connection at a time, so you can't run the engine on every instance. If we ever needed multiple consumers running live, it'd be either Kafka Connect with a consumer group, or leader election so only one instance holds the engine while the rest stand by.

CDC lag is the other thing I watch. Sits around 0.5s now, comfortably inside a user's "check stock, think, click buy" window. If it ever crept past a few seconds, stale reads would start causing real failed purchases, and at that point we'd add synchronous cache invalidation on the write path to bound read freshness.

Less likely, but worth naming: if anything external ever had to consume inventory events, the requirement stops being "populate our own cache" and becomes "deliver events to third parties with a stable contract." That's when outbox earns its keep, not now.

## Related

- ADR-001: the conditional UPDATE that this ADR's read cache complements.
- Benchmark data: [`redis-lua-archived.md`](../benchmarks/results/redis-lua-archived.md), [`redis-down-fallback-archived.md`](../benchmarks/results/redis-down-fallback-archived.md), [`cdc-read-cache.md`](../benchmarks/results/cdc-read-cache.md)