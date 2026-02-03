# ADR-001: Inventory Write Path — Conditional UPDATE over @Version

## Status

Accepted

## Context

Inventory deduction in TicketFlow is a single hot row under high write contention. During a flash sale, hundreds of concurrent requests hit the same `inventories` row. The deduction can never oversell, and it has to handle contention without burning cycles on wasted work.

The first version used JPA `@Version` optimistic locking. That approach has a structural problem when you point it at a single hot row. Every request reads the row (version included), decrements stock in memory, then writes back with a version check. Hibernate implements the check by tacking `WHERE id = ? AND version = ?` onto the UPDATE. If the version moved between the read and the write, zero rows match, Spring throws `ObjectOptimisticLockingFailureException`, and the request has to retry. On one hot row with 200 concurrent writers, that happened to 5.9% of requests (6,751 out of 114,781).

The thing is, Postgres already serializes access to that row through its internal row-level lock. Two concurrent UPDATEs on the same row queue up. The second one waits for the first to commit. The database handles serialization correctly on its own. `@Version` stacks a second layer on top. After the database finishes serializing, Hibernate asks whether the version still matches what was read earlier. On a hot row the answer is often "no," because plenty of other transactions committed between the read and the write. So the application throws out work the database already serialized, retries, and competes with new arrivals for the same slot.

That's double-serialization, and it's unnecessary here. If the check and the write happen in a single SQL statement, the database's row lock is enough on its own. No read-then-write gap for the version to drift through.

## Considered Options

### 1. @Version with exponential backoff retry

Keep the JPA approach. Add backoff with jitter to reduce the chance of colliding again on retry.

### 2. SELECT FOR UPDATE + manual stock check

Pessimistic locking. Grab an exclusive row lock, check stock in application code, then UPDATE. Serializes at the DB level. Costs two round-trips per request.

### 3. Conditional UPDATE in a single statement

Native SQL: `UPDATE ... SET available_stock = available_stock - :quantity WHERE available_stock >= :quantity`. The database evaluates the condition and applies the change in one atomic operation, returning the affected row count. 1 means success, 0 means insufficient.

### Options at a glance

| Option | Round-trips | Retry needed? | Lock held during |
|---|---|---|---|
| @Version + backoff | 2 (read + write) | Yes | (optimistic) |
| SELECT FOR UPDATE | 2 (select + update) | No | Application code |
| Conditional UPDATE | 1 | No | SQL execution |

## Decision

Going with option 3. Conditional UPDATE, implemented as `guardDeduct` in `InventoryRepository`.

```sql
UPDATE inventories
SET available_stock = available_stock - :quantity,
    version = version + 1,
    updated_at = NOW()
WHERE ticket_type_id = :ticketTypeId
  AND available_stock >= :quantity
```

The database grabs a row-level lock, evaluates the WHERE clause inside the lock, and either commits the write or returns 0 affected rows. One round-trip. No preceding read. No retry path. The caller reads the return value and maps it to `DeductionResult.SUCCESS` or `DeductionResult.INSUFFICIENT`.

The reason this works: the condition that actually matters (is there enough stock?) gets checked at the moment the lock is held, not at some earlier point when the row was read. No gap between check and write for anyone else to slip into.

### Why not @Version with retry

Backoff helps when conflicts are sporadic. It doesn't help with sustained contention on a single row. Under flash-sale load, retries just rejoin the queue they were supposed to drain. The 5.9% conflict rate at 200 VUs would get worse as concurrency grew, not better, because every retry now competes with new arrivals for the same version number.

`@Version` is really there for a different kind of problem — stale reads across multiple rows, long-lived sessions, editors where two users might clobber each other's changes. For a single-row atomic decrement, the row lock already serializes you. Stacking version checks on top just generates conflicts without buying any correctness you didn't already have.

### Why not SELECT FOR UPDATE

Two round-trips to do what one can. The conditional UPDATE gets the same serialization with half the database work. SELECT FOR UPDATE also holds the row lock longer, because between the SELECT and the UPDATE the application is sitting there doing stock arithmetic that the database could handle in a single statement.

## Evidence

**Benchmark comparison** (200 VUs, 500 tickets, 30s, same hardware):

| Metric | @Version | Conditional UPDATE |
|---|---|---|
| Lock conflicts | 6,751 (5.9%) | 0 |
| Contention handling | Application-level retry | Row lock queue |
| Blended p95 (all requests) | 180ms | 280ms |
| Oversell | 0 | 0 |

Full data: [`optimistic-lock-baseline-archived.md`](../benchmarks/results/optimistic-lock-baseline-archived.md), [`redis-down-fallback-archived.md`](../benchmarks/results/redis-down-fallback-archived.md)

The conditional UPDATE's blended p95 is higher than `@Version`'s, not lower. That looks like a regression. It isn't. It's the trade-off. Under `@Version`, a version conflict gets detected immediately after the UPDATE runs. Row locked, version doesn't match, 0 rows affected, done. The request fails fast without sitting in any queue. Under conditional UPDATE, every sold-out request waits in the lock queue, acquires the lock, and only then discovers stock is gone. No fast-fail path for contention. Per-request latency ends up higher, but every request gets a definitive answer on its first attempt, with zero conflicts and zero wasted work.

**What guardDeduct doesn't solve**: the sold-out storm. In the standalone benchmark (Redis stopped, 200 VUs), guardDeduct sold 500 tickets without a single conflict, but 36,823 sold-out requests still hit the database, each one acquiring the row lock just to discover stock was already gone.

**Throughput ceiling**: at 17ms p95 for a successful write (measured in `flash-sale-spike`), a single `inventories` row serializes at roughly 60 writes/sec under full contention. Flash-sale traffic exceeds that by 1 to 2 orders of magnitude. So the conditional UPDATE solves correctness and contention, but the throughput ceiling is why the write path alone can't carry the system. That's what ADR-002's read cache is for. It keeps 99.85% of sold-out traffic off the database.

**Test coverage:**

- `ConcurrentInventoryTest`: 200 threads buy from 50 tickets. Asserts `sold + remaining == initial_stock` (the oversell invariant) and `sold + insufficient + lockConflicts == THREADS` (every thread got exactly one outcome).
- `InsufficientStockCounterTest`: verifies the `insufficient_stock` Prometheus counter fires on the `dbDeduct → guardDeduct` production path when `affected == 0`, not on the `@Version` entity path.
## Consequences

The biggest win is that contention doesn't have to be paid for at the application layer. The database serializes the queue internally, so contention shows up as higher lock-wait latency, not as conflicts that every caller has to catch and retry. One round-trip per request, no read before the write.

`DeductionResult` also turns the `affected == 0` return value into a domain signal without using exceptions for control flow. The caller branches on an enum rather than a try/catch, which keeps the failure case in the type signature and the happy path out of exception handlers. And transactional consistency falls out of the `@Transactional` boundary for free. `guardDeduct` runs inside `OrderService.createOrder()`'s transaction, so if the Order INSERT that follows violates the `request_id` unique constraint, the whole transaction rolls back and the deduction undoes itself with it. `IdempotencyTest.same_requestId_concurrent_creates_exactly_one_order` verifies this under 50-way concurrent duplicate submission.

The costs.

Native SQL bypasses JPA's entity lifecycle, so `@PreUpdate` callbacks don't fire and `updated_at` has to be set explicitly in the query (`updated_at = NOW()`). I actually caught this as a bug during code review — the first `guardDeduct` didn't include `updated_at`, and the column stayed frozen at init time. Database-specific SQL is the other obvious coupling, and we accept it because TicketFlow is Postgres-only by design. And there are two deduction paths in the codebase now, `dbDeduct` and `deductStock`. The `@Version` path only exists for `ConcurrentInventoryTest`, which needs entity-level `deduct()` to test `@Version` conflict behavior directly. It's documented that way, and production traffic always goes through `dbDeduct()` → `guardDeduct()`.

## When We'd Revisit

Moving off Postgres would force a rewrite here, since the conditional UPDATE semantics don't translate cleanly across databases. Sharding inventory would also break the current setup, because once stock is split across rows, a one-row check stops being the whole answer. The other thing I keep an eye on is raw write rate: a single row serializes at around 60 writes/sec at this latency, and past that the row lock itself is the bottleneck, at which point no amount of read-side cleverness helps.

## Related

- Benchmark data: [`optimistic-lock-baseline-archived.md`](../benchmarks/results/optimistic-lock-baseline-archived.md)
- ADR-002 addresses what happens after stock runs out: the sold-out storm that `guardDeduct` alone can't prevent.