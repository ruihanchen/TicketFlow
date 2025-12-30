# Benchmark: Redis Lua Atomic Inventory Deduction

**Date:** 2025-12-29

## Environment

- **Host OS:** Windows 11 Home (Build 26200)
- **CPU:** Intel Core Ultra 9 275HX (2.70 GHz)
- **RAM:** 32GB @ 6400 MT/s
- **Storage:** 2TB NVMe SSD (High I/O)
- **Runtime:** Docker Engine v25+ (WSL 2 Backend)
- **Resource Limits (Per Service):**
    - **PostgreSQL:** 2.0 vCPUs, 2GB RAM (Cgroups limited)
    - **Redis:** Default (no explicit limit)
    - **TicketFlow App:** 2.0 vCPUs, 2GB RAM (Cgroups limited)
- **JVM Settings:** `-Xms2g -Xmx2g -XX:+UseG1GC`

**Script:** `docs/benchmarks/scripts/redis-lua.js`

## Parameters

| Parameter | Value                                                        |
|-----------|--------------------------------------------------------------|
| VUs       | 200                                                          |
| Duration  | 30s                                                          |
| Stock     | 500                                                          |
| DB Pool   | 50                                                           |
| Strategy  | Redis Lua atomic check-and-decrement + DB conditional UPDATE |

## Results

| Metric                  | Value          |
|-------------------------|----------------|
| Total requests          | 88,938         |
| Orders succeeded        | 500            |
| Sold out (409)          | 88,432 (99.4%) |
| Lock conflicts (503)    | 0              |
| Hard errors             | 0              |
| Oversell                | 0              |
| Throughput (total)      | ~2,903 req/s   |
| Throughput (successful) | ~16 req/s      |
| Latency avg             | 67ms           |
| Latency p(90)           | 73ms           |
| Latency p(95)           | 79ms           |

## Interpretation

500 tickets sold exactly. Zero oversell, zero lock conflicts, zero hard errors.

Total throughput reached 2,903 req/s, which is 2.4x the DB-only fallback (1,226 req/s)
under the same load. The gap comes from how sold-out checks are handled. With Redis,
a stock exhaustion check is a single `GET` in ~0.1ms that never touches the database.
In 30 seconds, 88,432 requests were rejected entirely in Redis, keeping the DB free
for the 500 actual order writes.

P95 latency of 79ms reflects two very different request types running together.
Successful orders (~500) averaged 437ms each, covering Redis DECRBY, DB conditional
UPDATE, and DB INSERT. Sold-out rejections (~88,400) averaged ~63ms, handled by a
Redis GET with no DB involvement. The 79ms P95 reflects that 99.4% of requests are
fast rejections.

## Raw Output

```
execution: local
   script: docs/benchmarks/scripts/redis-lua.js
 
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