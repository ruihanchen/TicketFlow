# ADR-006: Phase 1 Idempotency Strategy and the Decision to Shift to Redis in Phase 2

## Status

Accepted — Phase 1 limitation documented; Redis SETNX migration planned for Phase 2.

---

## Context

Idempotent order creation is a hard requirement: if a user double-submits, or the network retries a request, exactly one order must be created. Our Phase 1 strategy used two layers of defense:

1. **Application-layer pre-check** — `orderRepository.existsByRequestId(requestId)` before any write.
2. **Database unique constraint** — `UNIQUE` on `orders.request_id` as the last line of defense.

During concurrent integration testing (`IdempotencyTest`), we put this strategy under load: **50 threads submitted the exact same `requestId` simultaneously**, simulating an aggressive duplicate-request scenario (e.g., rapid button clicks, gateway retries). This exposed a chain of failures we had to reason through carefully before arriving at a principled decision.

---

## The Failure Chain

### Stage 1 — Pre-check Race Condition (Expected and Accepted)

Under concurrency, the "check-then-act" pattern is inherently racy. Threads read `existsByRequestId = false` before the first successful writer has committed, so multiple threads proceed past the pre-check simultaneously. This is the classic TOCTOU (Time-of-Check-Time-of-Use) problem. The database constraint is supposed to catch exactly these threads.

### Stage 2 — Database Constraint Works Correctly

The database behaved as designed. Exactly one thread committed successfully; all other threads received `DataIntegrityViolationException` from the unique constraint violation. **Zero duplicate orders were created.** Data integrity was never at risk.

### Stage 3 — Hibernate Session Poisoning (The Unexpected Wall)

We attempted to handle `DataIntegrityViolationException` gracefully — catching it, looking up the existing order by `requestId`, and returning it to the caller. This is the correct behavior from a product perspective: the client gets back the order that was already created.

Hibernate prohibits this. Once an SQL exception occurs inside a JPA transaction, Hibernate irrevocably marks the `Session` as *rollback-only*. Any subsequent read issued against that session — even a simple `SELECT` — results in one of two outcomes:

- `AssertionFailure: null id` if the Hibernate session state is corrupted before the exception surface.
- `UnexpectedRollbackException` thrown by `TransactionInterceptor` at commit time, overriding the application exception we carefully threw.

This is not a bug; it is a deliberate design constraint in the JPA specification. A session that has seen an SQL error cannot be trusted to maintain a consistent first-level cache, so Hibernate refuses further use.

### Stage 4 — The `REQUIRES_NEW` Dead End

We explored isolating the write operation in a nested transaction (`@Transactional(propagation = REQUIRES_NEW)`) to shield the outer session from poisoning. This prevents session corruption but introduces a different failure mode:

**Connection pool exhaustion under load.**

`REQUIRES_NEW` suspends the outer transaction and opens a second physical database connection. The formula generalizes:

```
required_connections = concurrent_requests × connections_per_transaction_boundary
```

At 50 concurrent threads with `REQUIRES_NEW`, the system needs 100 simultaneous connections. HikariCP's default pool size is 10. The result is a deadlock: all threads hold one connection waiting for a second, and no thread can make progress. `CannotCreateTransactionException` follows immediately.

Raising the pool size delays the problem but does not solve it. Any production traffic spike would reproduce the exhaustion at a higher thread count.

---

## Decision

**1. Do not restructure the transaction boundary to work around a framework constraint.**

Splitting a business transaction with `REQUIRES_NEW` for idempotency control is an anti-pattern. It converts a latency problem (optimistic lock retries) into a capacity problem (connection multiplication) and makes throughput inversely proportional to concurrency — exactly the wrong shape.

**2. Accept the Phase 1 behavior as a documented, bounded limitation.**

In Phase 1, concurrent duplicate requests that race past the pre-check will result in `UnexpectedRollbackException` propagating to the caller (HTTP 500), rather than a graceful "order already exists" response. This is an undesirable user experience. It is not a data integrity failure — the database prevents duplicate orders in every case.

The integration test (`IdempotencyTest`) reflects this reality honestly. Threads that hit the constraint race are counted as `idempotent_rejected` in test metrics, not as unexpected failures. The assertion that matters — `db_orders = 1` and `distinct_orderNos = 1` — passes cleanly.

**Test results as of Phase 1 baseline:**

```
[Idempotency Test] threads=50, success=40, idempotent_rejected=10,
                   unexpected_fail=0, distinct_orderNos=1, db_orders=1
```

No duplicate orders. No inventory leak. The correctness invariant holds.

**3. Relocate idempotency control to Redis in Phase 2.**

The root cause of all three failure stages above is that idempotency is being enforced *inside* a database transaction. This means the DB must both detect the duplicate and recover from detecting it — two responsibilities that conflict at the JPA layer.

The correct architecture separates these concerns:

```
[Request arrives]
      │
      ▼
[Redis SETNX on requestId]   ← idempotency enforced HERE, before any DB transaction
      │
   already set?
      │ yes                        │ no
      ▼                            ▼
[Return cached order]     [Open DB transaction]
                          [Deduct inventory]
                          [Insert order]
                          [Commit]
```

Redis `SETNX` (SET if Not eXists) is atomic by nature. The first request sets the key and proceeds; every subsequent request with the same `requestId` reads the key and short-circuits before touching the database. No JPA session is ever opened for duplicates. No connection pool slots are consumed for no-op work. The session poisoning problem class is eliminated entirely, not worked around.

---

## Consequences

### Positive

- Phase 1 production code maintains clean, standard ACID transaction boundaries with no structural compromises.
- Data integrity is strictly preserved under all concurrency levels. Zero duplicate orders are possible given the DB constraint as final backstop.
- The failure chain documented here provides a concrete, reproducible justification for the Redis introduction in Phase 2. This avoids the common portfolio anti-pattern of adding Redis "because it's fast" with no demonstrated need.
- The connection pool formula (`required_connections = concurrent_requests × connections_per_boundary`) is now a named, reasoned constraint that informs all future capacity planning decisions in this codebase.

### Negative

- In Phase 1, users submitting aggressive duplicate requests receive an HTTP 500 instead of a graceful idempotent response. This is a product quality gap, not a data safety gap.
- The integration test for idempotency requires special exception handling (`catch UnexpectedRollbackException`) to produce readable metrics. This adds test-layer complexity that should not exist in a well-designed system — and will be removed once Phase 2 Redis idempotency is in place.

---

## References

- `IdempotencyTest.java` — integration test that produced the results above
- `OrderService.java` — Phase 1 implementation, `createOrder()` with `noRollbackFor` and DB constraint fallback
- `InventoryAdapter.java` — `REQUIRES_NEW` boundary and its connection implications
- ADR-002 — Inventory optimistic locking strategy (related concurrency reasoning)
- Phase 2 work item: Redis SETNX idempotency fast path (P0 priority)
