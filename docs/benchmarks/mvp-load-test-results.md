# Load Test Results

## Environment

| | |
|--|--|
| Machine | Windows 11 developer laptop, Docker containers |
| JVM | Java 21.0.9 |
| DB | PostgreSQL 16 (container) |
| Cache | Redis 7 (container) |
| Broker | Kafka 3.8 KRaft (container) |
| Tool | k6 |
| VUs | 40, `sleep(0.1)` between iterations |
| Duration | 60s per run |
| HikariCP | 80 connections |

40 VUs with `sleep(0.1)` is a deliberate constraint. Windows limits the TCP
ephemeral port range to ~16,000 ports — 500 VUs without sleep exhausts it in
seconds. The relative performance gain between Lua and DB locking is architectural — it holds regardless of the exact VU count.

---

## Phase 1 — Optimistic Locking

### Integration test (2026-03-16)

`ConcurrentInventoryTest`: 200 threads, 50 tickets, Testcontainers.

| | |
|--|--|
| Tickets sold | 50 (exact) |
| Overselling | 0 |
| Lock conflicts | Heavy WARN logs throughout |
| Time to sell 50 tickets | **~33 seconds** |

### k6 (2026-03-20)

| Metric | |
|--------|--|
| Total requests | 20,118 |
| QPS | 331.5 |
| Successful orders | 3,136 |
| **Success rate** | **15.59%** |
| Lock conflicts (30005) | **16,976** |
| Hard errors | 0 |
| Error rate | 0.00% |
| p50 | 15ms |
| p95 | 25ms |
| p99 | 30ms |

16,976 requests rejected as `LOCK_CONFLICT`. The system isn't failing — it's
wasting 84% of capacity on retry collisions at the DB row level.

---

## Phase 2 — Redis Lua

### k6 (2026-03-20)

| Metric | Phase 1 | Phase 2 | Δ |
|--------|---------|---------|---|
| Total requests | 20,118 | 20,677 | — |
| QPS | 331.5 | 340.5 | +2.7% |
| Successful orders | 3,136 | **20,671** | **+559%** |
| **Success rate** | **15.59%** | **99.97%** | **+540 pp** |
| Lock conflicts | 16,976 | **0** | **−100%** |
| Hard errors | 0 | 0 | — |
| Error rate | 0.00% | 0.00% | — |
| p50 | 15ms | 11ms | −27% |
| p95 | 25ms | 23ms | −8% |
| p99 | 30ms | 36ms | +20% |

QPS barely moved. The bottleneck wasn't hardware — it was retries. Lua's atomic
deduction eliminates the race condition that forces them.

**p99 note**: Phase 1's 30ms p99 is artificially low — most requests short-circuit
as `LOCK_CONFLICT` without doing real work. Phase 2's 36ms reflects the actual
full path.

**Integration test**: same 200 threads, 50 tickets, `RedisInventoryAdapter`
active. Under 3 seconds. Zero `OptimisticLockException` logs. Zero overselling.

---

## Phase 2 — Redis Failure (Graceful Degradation)

Redis container stopped before this run.

| Metric | Phase 2 Redis | Redis DOWN |
|--------|--------------|------------|
| QPS | 340.5 | 266.2 |
| Successful orders | 20,671 | 5,679 |
| **Success rate** | **99.97%** | **35.11%** |
| Lock conflicts | 0 | 10,492 |
| Hard errors | 0 | **0** |
| **Error rate** | **0.00%** | **0.00%** |
| p50 | 11ms | 35ms |
| p99 | 36ms | 148ms |

`RedisInventoryAdapter` caught the connection failures and fell back to
`InventoryAdapter` (optimistic locking). System degraded to Phase 1 behavior —
expected, since that's the fallback. No 5xx. No crash.

`RedisDegradationTest`: 200 threads, Redis mocked to throw on every call.
`sold=50, overselling=0`. Fallback tested, not assumed.

---

## Correctness (Testcontainers, 2026-03-20)

Real PostgreSQL, Redis, and Kafka containers. No H2 or mocks.

| | Test | |
|--|-----|--|
| No overselling — Redis active, 200 threads | `ConcurrentInventoryTest` | ✅ |
| No overselling — Redis down, 200 threads | `RedisDegradationTest` | ✅ |
| Idempotent orders — 50 concurrent duplicates | `IdempotencyTest` | ✅ |
| Async restore — user cancel | `KafkaInventoryRestoreTest` | ✅ |
| Async restore — system timeout | `KafkaInventoryRestoreTest` | ✅ |
| No double-restore on Kafka redelivery | `KafkaInventoryRestoreTest` | ✅ |
| Timeout idempotency — no duplicate events | `OrderTimeoutTest` | ✅ |

12/12 pass.

---

## Reproduce

```bash
cd docker && docker-compose up -d
./mvnw spring-boot:run -pl app

# Phase 2 (default — RedisInventoryAdapter is @Primary)
k6 run docs/benchmarks/scripts/phase2-redis.js

# Phase 1 — swap @Primary to InventoryAdapter, restart
k6 run docs/benchmarks/scripts/phase1-baseline.js

# Fallback — restore @Primary to RedisInventoryAdapter, restart, then:
docker stop ticketflow-redis
k6 run docs/benchmarks/scripts/phase2-fallback.js
docker start ticketflow-redis

# Integration tests
./mvnw test
```
