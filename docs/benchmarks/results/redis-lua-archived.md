# Archived: Redis Lua Atomic Inventory Deduction

> This benchmark measures an architecture that was later removed.
> At the time (2025-12-29), `InventoryAdapter.deductStock()` ran a Redis Lua
> script for atomic check-and-decrement, then wrote through to Postgres via
> a conditional UPDATE. That dual-write design is gone: the write path now
> goes straight to Postgres, and Redis is populated exclusively by the
> Debezium CDC pipeline on the read side.
>
> These numbers are kept as a record of the dual-write iteration — including
> why the performance looked good and why we pulled it anyway.
> For the current write path, see `write-path-db-only.md`.
 
---

**Date:** 2025-12-29
**Script:** `docs/benchmarks/scripts/redis-lua-archived.js`

## Environment

- **OS:** Windows 11 Home (Build 26200)
- **CPU:** Intel Core Ultra 9 275HX (2.70 GHz)
- **RAM:** 32 GB @ 6400 MT/s
- **Storage:** 2 TB NVMe SSD
- **Runtime:** Docker Engine v25+ (WSL 2)
- **Resource limits:** PostgreSQL 2.0 vCPU / 2 GB, App 2.0 vCPU / 2 GB
- **JVM:** `-Xms2g -Xmx2g -XX:+UseG1GC`

## Parameters

| Parameter | Value                                                        |
|-----------|--------------------------------------------------------------|
| VUs       | 200                                                          |
| Duration  | 30s                                                          |
| Stock     | 500                                                          |
| DB Pool   | 50                                                           |
| Strategy  | Redis Lua atomic check-and-decrement + DB conditional UPDATE |

## Results

| Metric               | Value          |
|----------------------|----------------|
| Total requests       | 88,938         |
| Orders succeeded     | 500            |
| Sold out (409)       | 88,432 (99.4%) |
| Lock conflicts (503) | 0              |
| Hard errors          | 0              |
| Oversell             | 0              |
| Throughput (total)   | ~2,903 req/s   |
| Latency avg          | 67ms           |
| Latency p(95)        | 79ms           |

## What these numbers show

500 tickets sold exactly. Zero oversell, zero lock conflicts. Throughput hit
2,903 req/s — 2.4× the DB-only number (1,226 req/s) under the same load.

That gap comes entirely from sold-out handling. With Redis in front, a sold-out
check is a single `GET` at ~0.1ms with no DB involvement. 88,432 requests were
rejected in Redis, leaving the database free for the 500 actual writes. P95 of
79ms is a blended number across two very different populations: successful orders
(~500) averaged 437ms each (Lua DECRBY + DB UPDATE + DB INSERT); sold-out
rejections (~88,400) averaged ~63ms (Redis GET, no DB).

The numbers looked good. We shipped it. Then we found the problem.

## Why this design was pulled

Redis DECRBY followed by a DB conditional UPDATE has no atomic boundary between
the two stores. If the process dies between those two operations — OOM kill,
container eviction, network partition, anything — Redis and Postgres end up
permanently inconsistent. The compensation code in `OrderService.safeReleaseStock()`
was supposed to handle rollbacks with a Redis INCRBY, but that path was only
exercised in happy-path tests. The actual failure mode — Redis write succeeds,
DB write never happens — was never tested against a real crash.

A counter audit during observability work made it concrete. Redis overcounting
relative to Postgres had no automatic fix and no reliable detection under burst
load. The reconciliation job could silently correct Redis undercounting, but the
other direction was a blind spot.

The fix wasn't to patch the compensation logic. It was to stop doing dual-writes.
Postgres is now the only inventory writer. Debezium streams every committed WAL
change to Redis asynchronously. There's no window where the two stores can diverge
on a write because only one of them participates in writes.

The 2.4× throughput difference is real. The current architecture recovers it on
the read side instead: `GET /api/v1/ticket-types/{id}/stock` serves from Redis
at sub-millisecond latency, so clients that poll stock before buying never hit
the write path with a doomed request in the first place.

## Raw Output

```
execution: local
   script: docs/benchmarks/scripts/redis-lua-archived.js
 
scenarios: (100.00%) 1 scenario, 200 max VUs, 1m0s max duration
         * flash_sale: 200 looping VUs for 30s (gracefulStop: 30s)
 
█ THRESHOLDS
 
    hard_errors
    ✓ 'count==0' count=0
 
 
█ TOTAL RESULTS
 
    CUSTOM
    hard_errors....................: 0      0/s
    order_latency..................: avg=67.43ms  min=3.09ms med=63.32ms  max=1.06s p(90)=73.49ms  p(95)=78.56ms
    order_success..................: 500    16.3197/s
    sold_out.......................: 88432  2886.367505/s
 
    HTTP
    http_req_duration..............: avg=67.43ms  min=3.09ms med=63.32ms  max=1.06s p(90)=73.49ms  p(95)=78.56ms
      { expected_response:true }...: avg=436.54ms min=5.16ms med=431.69ms max=1.06s p(90)=578.15ms p(95)=621.12ms
    http_req_failed................: 99.43% 88432 out of 88938
    http_reqs......................: 88938  2902.883042/s
 
    EXECUTION
    iteration_duration.............: avg=67.51ms  min=3.09ms med=63.4ms   max=1.07s p(90)=73.58ms  p(95)=78.69ms
    iterations.....................: 88932  2902.687206/s
    vus............................: 200    min=200       max=200
 
    NETWORK
    data_received..................: 36 MB  1.2 MB/s
    data_sent......................: 36 MB  1.2 MB/s
 
running (0m30.6s), 000/200 VUs, 88932 complete and 0 interrupted iterations
flash_sale ✓ [======================================] 200 VUs  30s
```