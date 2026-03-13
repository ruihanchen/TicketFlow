# ADR-004: Optimistic Locking for Concurrent Inventory Control

**Date**: 2026-03  
**Status**: Accepted  
**Author**: Chen (chendev)

---

## Context

When a flash sale begins, thousands of users concurrently attempt to purchase tickets.
Every successful purchase must deduct from a shared inventory counter.

This is a classic concurrent write problem. The system must guarantee:
1. Available stock never goes below zero (no overselling)
2. Every legitimate purchase that finds stock available should succeed
3. The solution must not become a bottleneck under load

---

## The Overselling Problem Illustrated

Without concurrency control:

```
Time 0: availableStock = 1
Thread A reads: availableStock = 1 (sufficient)
Thread B reads: availableStock = 1 (sufficient)
Thread A writes: availableStock = 0 (deducted 1)
Thread B writes: availableStock = 0 (deducted 1 from stale value!)
Result: 2 tickets sold, 0 remaining → oversold by 1
```

Any solution must prevent Thread B from writing based on a value that Thread A
has already modified.

---

## Options Considered

### Option A: Pessimistic Locking (`SELECT FOR UPDATE`)

Acquires a database row lock before reading. No other transaction can read or
write the row until the lock is released.

```sql
SELECT * FROM inventories WHERE ticket_type_id = ? FOR UPDATE;
UPDATE inventories SET available_stock = available_stock - ? WHERE ticket_type_id = ?;
```

**Pros**: Guaranteed serialization. No retry logic needed.  
**Cons**:
- Row locks are held for the entire transaction duration. Under flash sale load,
  thousands of transactions queue behind a single lock. Throughput collapses.
- Lock contention causes connection pool exhaustion — threads wait for locks,
  connections sit idle, new requests are rejected.
- Deadlock risk increases with lock count and transaction complexity.
- This approach scales vertically (bigger database) but not horizontally.

**Verdict**: Correct but does not scale. Acceptable for low-traffic systems,
unacceptable for flash sales.

### Option B: Optimistic Locking with `@Version`

No lock acquired on read. On write, the database checks that the version has
not changed since the record was read:

```sql
UPDATE inventories
SET available_stock = ?, version = version + 1
WHERE ticket_type_id = ? AND version = ?;  ← version check
-- If 0 rows updated: another transaction modified this record → retry
```

JPA handles this automatically via `@Version`:

```java
@Entity
public class Inventory {
    @Version
    private Integer version;  // JPA increments on every UPDATE
}
```

If two transactions read `version = 5` and both attempt to update:
- Transaction A succeeds: version becomes 6
- Transaction B fails: `OptimisticLockException` (version 5 no longer exists)

**Pros**:
- No locks held during read. Concurrent reads are fully parallel.
- Contention only causes retries, not lock queues.
- Scales horizontally — multiple application instances can safely write.
- Natural fit for Phase 2: optimistic locking logic moves entirely to Redis.

**Cons**:
- High contention means high retry rates. Under extreme flash sale load
  (thousands of concurrent writes), most requests will retry repeatedly,
  degrading throughput. This is the known limitation of optimistic locking.
- Retry logic adds code complexity.

### Option C: Redis atomic operations (Lua script)

```lua
local stock = redis.call('GET', KEYS[1])
if tonumber(stock) >= tonumber(ARGV[1]) then
    redis.call('DECRBY', KEYS[1], ARGV[1])
    return 1
else
    return 0
end
```

Redis executes Lua scripts atomically — no race conditions possible.

**Pros**: Highest throughput. Redis single-threaded command processing eliminates
all write contention. This is the industry standard for flash sale inventory.  
**Cons**: Requires Redis infrastructure. Adds cache-database synchronization complexity.
Not appropriate for Phase 1 where the goal is validating business logic, not
optimizing throughput.

---

## Decision

**Option B (Optimistic Locking) for Phase 1, with explicit migration path to Option C.**

The `Inventory` entity carries a `@Version` field. JPA handles version checking
transparently. The `InventoryAdapter` catches `OptimisticLockingFailureException`
and surfaces it as a retryable `BizException`:

```java
try {
    inventory.deduct(quantity);
    inventoryRepository.save(inventory);
} catch (OptimisticLockingFailureException e) {
    throw BizException.of(ResultCode.INVENTORY_LOCK_FAILED,
            "High demand — please try again");
}
```

This is deliberately not hidden from the user. "Please try again" is honest —
under flash sale conditions, the user genuinely needs to retry.

**Phase 2 migration**: The `InventoryAdapter` implementation is replaced.
The `InventoryPort` interface does not change. `OrderService` does not change.
This is the Port & Adapter pattern paying its dividend (see ADR-003).

---

## Why Not Skip Directly to Redis?

This is the most common objection to this decision.

The answer is about **validation sequencing**. Phase 1 goals are:
1. Validate that the business logic is correct
2. Validate that the domain model handles edge cases
3. Validate that the state machine covers all transitions

Introducing Redis in Phase 1 adds infrastructure complexity that obscures
business logic bugs. A Redis connection failure looks identical to a business
logic error if the layers are not clean.

Optimistic locking in Phase 1 means: if the system produces wrong results,
the bug is in business logic, not infrastructure. This makes debugging deterministic.

---

## Known Limitations and Acceptance Criteria

This decision is accepted with the following explicit limitations:

| Scenario | Phase 1 Behavior | Acceptability |
|----------|-----------------|---------------|
| Low concurrency (< 100 concurrent) | Correct, performant | ✅ Acceptable |
| Medium concurrency (100–1,000 concurrent) | Correct, some retries | ✅ Acceptable |
| Flash sale (> 1,000 concurrent) | Correct, high retry rate, degraded throughput | ⚠️ Known limitation |
| Overselling | Impossible — version check prevents it | ✅ Always correct |

**Correctness is non-negotiable. Throughput is a Phase 2 concern.**

---

## Consequences

**Positive**:
- Zero overselling — guaranteed by both application logic and database constraint
- No infrastructure dependencies beyond PostgreSQL
- Phase 2 Redis migration requires changing one file (`InventoryAdapter`)

**Negative**:
- Under flash sale load, high retry rates will degrade throughput
- Users may see "please try again" errors that would not occur with Redis

**Accepted trade-off**: Phase 1 is not designed for flash sale throughput.
It is designed to validate correct behavior under moderate load.
Phase 2 addresses throughput without changing correctness guarantees.
