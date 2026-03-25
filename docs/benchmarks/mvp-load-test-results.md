# Load Test Results

## Setup

| | |
|--|--|
| Machine | Windows 11 laptop, everything in Docker |
| JVM | Java 21 |
| DB | PostgreSQL 16 (container, shared CPU) |
| Cache | Redis 7 (container) |
| Broker | Kafka 3.8 KRaft (container) |
| Tool | k6 |
| Load | 40 VUs, `sleep(0.1)`, 60s per run |
| DB Pool | 50 connections (post-refactoring) |

Constraint: VUs capped at 40. Windows TCP ephemeral port range is ~16,000 —
higher VU counts without sleep exhaust it in seconds. The benchmark measures
architectural improvement between phases, not absolute throughput on this
hardware.

---

## Phase 1 — Optimistic Locking (2026-03-24)

Every inventory write goes through PostgreSQL with `@Version` optimistic
locking. Correct — never oversold — but most requests just burn CPU retrying.

| Metric | |
|--------|--|
| Total requests | 21,225 |
| QPS | 349.5 |
| Successful orders | 2,818 |
| **Success rate** | **13.28%** |
| Lock conflicts | **18,401** |
| Error rate | 0.00% |
| p50 | 12ms |
| p99 | 26ms |

87% of capacity lost to retry collisions at the DB row level. The system isn't
failing — it's wasting time.

---

## Phase 2 — Redis Lua + Transactionless Fast Path (2026-03-24)

Redis Lua handles the check-and-decrement atomically. `deductStock()` runs
outside `@Transactional` — rejected requests never touch the DB connection pool.
DCL prevents thundering DB reads on cache miss.

| Metric | Phase 1 | Phase 2 | Δ |
|--------|---------|---------|---|
| QPS | 349.5 | **361.2** | +3% |
| Successful orders | 2,818 | **22,008** | +681% |
| **Success rate** | **13.28%** | **99.97%** | +87 pp |
| Lock conflicts | 18,401 | **0** | gone |
| Error rate | 0.00% | 0.00% | — |
| p50 | 12ms | **7ms** | −42% |
| p95 | 20ms | **15ms** | −25% |
| p99 | 26ms | **29ms** | +12% |

QPS barely moved — the machine was never the bottleneck. The bottleneck was
retries (Phase 1) and connection waste (pre-refactoring Phase 2).

p99 went slightly up: Phase 1's 26ms is misleadingly low because most requests
short-circuit as lock conflicts without doing real work. Phase 2's 29ms is the
actual full-path latency.

p50 dropped 42%: this is directly from removing `REQUIRES_NEW`. One fewer
connection acquire + transaction boundary per request.

**Pool size: 50 connections.** Before the transaction boundary refactoring, this
same workload required 420. The difference: rejected requests used to grab a
DB connection, wait for Redis, get told "no stock", and release the connection
having done nothing useful. Now they never touch the pool.

Integration test (200 threads, 50 tickets, pool=50): sold=50, oversold=0,
lock conflicts=0, cache miss DB reads=1.

---

## Phase 2 — Redis DOWN (2026-03-24)

Stopped the Redis container before this run. `RedisInventoryAdapter` catches the
connection failure and delegates to `InventoryAdapter` (DB optimistic locking).

| Metric | Redis UP | Redis DOWN |
|--------|----------|------------|
| QPS | 361.2 | 263.9 |
| Successful orders | 22,008 | 5,837 |
| Success rate | 99.97% | 36.34% |
| Lock conflicts | 0 | 10,220 |
| Error rate | 0.00% | **0.00%** |
| p50 | 7ms | 38ms |
| p99 | 29ms | 224ms |

System degrades to roughly Phase 1 behavior. Slower, lower success rate, but
no 500s, no overselling, no crash. Redis is a soft dependency.

`RedisDegradationTest` (200 threads, Redis mocked to throw on every call):
sold=50, overselling=0.

---

## Correctness (Testcontainers, 2026-03-24)

All tests run against real PostgreSQL, Redis, and Kafka containers.

| Test | What it proves |
|------|---------------|
| `ConcurrentInventoryTest` (Redis active, 200 threads) | sold=50, oversold=0, 1 cache miss DB read |
| `RedisDegradationTest` (Redis down, 200 threads) | sold=50, oversold=0 |
| `IdempotencyTest` (50 concurrent same-requestId) | 1 order created, 49 rejected |
| `KafkaInventoryRestoreTest` — user cancel | stock restored |
| `KafkaInventoryRestoreTest` — system timeout | stock restored |
| `KafkaInventoryRestoreTest` — duplicate delivery | no double-restore |
| `OrderTimeoutTest` — idempotency | second run publishes 0 events |

12/12 pass.

---

## Reproduce

```bash
cd docker && docker-compose up -d postgres redis kafka
./mvnw spring-boot:run -pl app

# Phase 2 default
k6 run docs/benchmarks/scripts/phase2-redis.js

# Fallback test
docker stop ticketflow-redis
k6 run docs/benchmarks/scripts/phase2-fallback.js
docker start ticketflow-redis

# Integration tests
./mvnw test
```
