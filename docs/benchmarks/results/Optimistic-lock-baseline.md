# Benchmark: @Version Optimistic Locking Baseline

**Date:** 2025-12-19

## Environment

- **Host OS:** Windows 11 Home (Build 26200)
- **CPU:** Intel Core Ultra 9 275HX (2.70 GHz)
- **RAM:** 32GB @ 6400 MT/s
- **Storage:** 2TB NVMe SSD (High I/O)
- **Runtime:** Docker Engine v25+ (WSL 2 Backend)
- **JVM Settings:** `-Xms2g -Xmx2g -XX:+UseG1GC`

**Script:** `docs/benchmarks/scripts/optimistic-lock-baseline.js`

## Parameters

| Parameter | Value                                          |
|-----------|------------------------------------------------|
| VUs       | 200                                            |
| Duration  | 30s                                            |
| Stock     | 500                                            |
| DB Pool   | 50                                             |
| Strategy  | @Version optimistic lock, no server-side retry |

## Results

| Metric                  | Value            |
|-------------------------|------------------|
| Total requests          | 114,781          |
| Orders succeeded        | 500              |
| Lock conflicts (503)    | 6,751 (5.9%)     |
| Sold out (409)          | 107,524 (93.7%)  |
| Hard errors             | 0                |
| Oversell                | 0                |
| Throughput (total)      | ~3,768 req/s     |
| Throughput (successful) | ~16 req/s        |
| Latency avg             | 52ms             |
| Latency p(90)           | 73ms             |
| Latency p(95)           | 180ms            |

## Interpretation

Correctness holds — zero oversell, zero hard errors, exactly 500 tickets sold.

With 500 tickets and 200 concurrent users, stock depletes within the first few seconds. After that, the system spends the remaining ~28 seconds doing nothing useful: 107,524 requests each execute a full DB round-trip (SELECT the inventory row, attempt UPDATE WHERE available_stock >= 1, get affected=0, return 409) just to confirm what a single in-memory check could have answered in microseconds.

The 6,751 lock conflicts happen during the initial burst while stock is still being depleted. At 200 writers on one row, only one wins per version increment — the rest get 503 and the client retries. This is the expected cost of optimistic locking under heavy contention.

But the bigger problem isn't the lock conflicts — it's the 107,524 sold-out queries that keep hitting the DB after there's nothing left to sell. The DB has no way to short-circuit these without an external cache layer sitting in front of it. That's what motivates introducing Redis: once Redis stock reaches 0, every subsequent request gets rejected from memory without a single DB connection being opened.

## Raw Output

```
execution: local
   script: docs/benchmarks/scripts/optimistic-lock-baseline.js
 
scenarios: (100.00%) 1 scenario, 200 max VUs, 1m0s max duration
         * flash_sale: 200 looping VUs for 30s (gracefulStop: 30s)
 
█ THRESHOLDS
 
    hard_errors
    ✓ 'count==0' count=0
 
 
█ TOTAL RESULTS
 
    CUSTOM
    hard_errors....................: 0      0/s
    lock_conflicts.................: 6751   221.615171/s
    order_latency..................: avg=52.13ms  min=4.67ms med=41.49ms  max=704.9ms  p(90)=73.45ms  p(95)=179.54ms
    order_success..................: 500    16.413507/s
    sold_out.......................: 107524 3529.691849/s
 
    HTTP
    http_req_duration..............: avg=52.13ms  min=4.67ms med=41.49ms  max=704.9ms  p(90)=73.46ms  p(95)=179.53ms
      { expected_response:true }...: avg=167.62ms min=4.93ms med=149.94ms max=534.03ms p(90)=228.26ms p(95)=296.47ms
    http_req_failed................: 99.55% 114275 out of 114781
    http_reqs......................: 114781 3767.917489/s
 
    EXECUTION
    iteration_duration.............: avg=52.28ms  min=5.19ms med=41.62ms  max=704.9ms  p(90)=73.59ms  p(95)=179.97ms
    iterations.....................: 114775 3767.720527/s
    vus............................: 200    min=200              max=200
 
    NETWORK
    data_received..................: 46 MB  1.5 MB/s
    data_sent......................: 46 MB  1.5 MB/s
 
running (0m30.5s), 000/200 VUs, 114775 complete and 0 interrupted iterations
flash_sale ✓ [======================================] 200 VUs  30s
```