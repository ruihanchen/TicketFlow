# TicketFlow

Flash-sale ticketing backend. The kind where 200 people hit "buy" at the same
second and you'd better not sell ticket #51 when you only have 50.

Java 21 · Spring Boot 3.3.5 · PostgreSQL · Redis 7 · Kafka (KRaft) · Testcontainers

---

## Numbers

k6, 40 VUs, 60s. Everything running in Docker on one Windows laptop.

| | Phase 1 (DB only) | Phase 2 (Redis + DB) | Redis DOWN |
|--|-------------------|---------------------|------------|
| Success rate | 13% | **99.97%** | 36% |
| p50 | 12ms | **7ms** | 38ms |
| p99 | 26ms | 29ms | 224ms |
| Lock conflicts | 18,401 | **0** | 10,220 |
| DB pool size | 420 | **50** | 50 |
| Error rate | 0% | 0% | **0%** |

The last column matters: Redis goes down, the system gets slow, but nothing
breaks. No 500s, no overselling, no data loss.

12/12 integration tests pass against real containers (Testcontainers). No H2,
no mocks for infrastructure.

---

## What this project actually is

Phase 1 was a straightforward Spring Boot + PostgreSQL app with optimistic
locking. It worked — zero overselling — but 87% of requests under load just
bounced off the lock and wasted everyone's time.

Phase 2 was about fixing that. Three things changed:

**1. Redis Lua for inventory.** Moved the check-and-decrement into a Lua script
so Redis handles it atomically. No more retry storms. Success rate went from 13%
to 99.97%.

**2. Ripped the transaction boundary apart.** This was the big one. I had
`@Transactional` on the order creation method, which meant every request grabbed
a DB connection *before* even talking to Redis. Requests that Redis rejected
(stock gone) still held a connection the whole time — for nothing. And the
adapter used `REQUIRES_NEW`, so successful requests held *two* connections.
200 threads × 2 connections = I needed a pool of 420 just to not deadlock.

After refactoring: Redis deduction runs with no `@Transactional`. Only requests
that actually pass the Redis gate open a DB transaction. Pool went from 420 to
50. p50 dropped from 11ms to 7ms just from removing the `REQUIRES_NEW` overhead.

**3. Fixed a cache loading bug I created.** When Redis is cold (first request
after startup), the Lua script returns "cache miss" and the app loads stock from
DB into Redis. My first version used a plain `SET`. Under 200 concurrent
threads, they all hit the miss at once, all read DB, and the last `SET` won —
overwriting deductions that earlier threads already made. Redis thought there
was more stock than reality. DB guard write caught it (no overselling), but
Redis was wrong until the reconciler fixed it.

I tried adding a pre-check (`GET` before the DB read) plus `SETNX` instead of
`SET`. The write race was fixed, but the logs showed 236 threads still read DB —
the pre-check was useless because all 200 threads arrived in the same
microsecond before the first one finished. Ended up with DCL
(Double-Checked Locking) with per-ticketTypeId locks. Now exactly 1 thread reads
DB per cold start, the other 199 wait on the lock and find the cache already
populated.

---

## How the order flow works now

```
createOrder()                          ← not @Transactional
  ├─ Redis SETNX idempotency check     ← 0 DB connections
  ├─ Redis Lua deductStock()           ← 0 DB connections
  │    └─ cache miss? → DCL + SETNX   ← 1 DB read, exactly once
  └─ self.persistOrder()               ← @Transactional starts here
       ├─ DB guard write               ← same connection
       └─ order insert                 ← same connection, commit, done
```

Rejected requests (99% when stock runs out) never touch the database. They
bounce off Redis and go home. This is the whole point of the refactoring — not
faster QPS, but fewer wasted resources.

On cancel, the order status update publishes an `OrderCancelledEvent` via
`@TransactionalEventListener(AFTER_COMMIT)`. A Kafka consumer picks it up and
restores inventory using a Lua script that bundles idempotency check + stock
increment in one atomic command. Caught a fun bug here: the consumer originally
called `releaseStock()` which does its own Redis `INCRBY` — but the Lua script
had already incremented. Inventory went up by 2 per cancellation. Fixed by
adding `releaseStockDbOnly()` to the port interface.

---

## What's not solved (and why)

**JVM crash between DB commit and Kafka send.** If the process dies right after
committing the order cancellation but before the Kafka message gets out, the
inventory never comes back. The fix is Transactional Outbox — write the message
to a DB table in the same transaction, poll it out to Kafka separately. I know
how to build it, but it's a meaningful amount of code (new table, poller,
cleanup job) for a failure mode that requires a JVM crash at a specific 2ms
window. Deferred to Phase 3.

**Timeout polling has up to 59s lag.** Orders expire 15 minutes after creation,
but the polling job runs every 60 seconds. A Redis ZSET delayed queue would
make it near-instant. Deferred.

**Redis Cluster would break the Kafka consumer.** The idempotency Lua script
operates on two keys with different prefixes. In a cluster, they'd land on
different slots → `CROSSSLOT` error. Fix is Hash Tag alignment in the key
naming. Noted in code comments, not implemented because we're on single-node
Redis.

**Reconciler can temporarily overstate stock during in-flight transactions.**
If the reconciler runs while requests are between Redis deduction and DB commit,
it sees Redis < DB and "corrects" Redis upward. The DB guard write still
prevents overselling, but users might briefly see stock that isn't really
available. Acceptable for now — the reconciler runs every 5 minutes and the
window is milliseconds.

---

## Architecture decisions

8 ADRs document the reasoning behind every non-obvious choice. The ones worth
reading:

- [ADR-003](docs/decisions/ADR-003-port-adapter-pattern.md) — Why inventory
  deduction goes through a Port interface (paid off when swapping DB adapter
  for Redis adapter with zero changes to OrderService)
- [ADR-007](docs/decisions/ADR-007-phase2-high-concurrency.md) — Redis Lua,
  Kafka async cancellation, consumer idempotency, the +2 bug
- [ADR-008](docs/decisions/ADR-008-transactionless-fast-path.md) — Transaction
  boundary refactoring (420→50 pool), DCL cache loading (236→1 DB reads), and
  why we didn't use Caffeine or Guava Striped for locks

---

## Run it

```bash
cd docker && docker-compose up -d postgres redis kafka
./mvnw spring-boot:run -pl app

# Benchmark
k6 run docs/benchmarks/scripts/phase2-redis.js

# Tests (12/12)
./mvnw test
```
