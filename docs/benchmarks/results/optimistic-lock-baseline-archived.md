# Archived: @Version Optimistic Locking Baseline

> This benchmark measures an architecture that no longer exists on the write path.
> At the time (2025-12-19), `POST /api/v1/orders` went through
> `InventoryService.deductStock()` using JPA `@Version` for concurrency control.
> That path was replaced by a conditional UPDATE in `InventoryAdapter.deductStock()`,
> which is atomic at the row level and never returns a lock conflict.
> Running `optimistic-lock-baseline-archived.js` against current code will show
> 0 lock conflicts — not because the script is broken, but because the 503 path
> is unreachable.
>
> These numbers are the baseline that motivated moving away from `@Version`.
> For the current write path, see `write-path-db-only.md`.
 
---

**Date:** 2025-12-19
**Script:** `docs/benchmarks/scripts/optimistic-lock-baseline-archived.js`

## Environment

- **OS:** Windows 11 Home (Build 26200)
- **CPU:** Intel Core Ultra 9 275HX (2.70 GHz)
- **RAM:** 32 GB @ 6400 MT/s
- **Storage:** 2 TB NVMe SSD
- **Runtime:** Docker Engine v25+ (WSL 2)
- **JVM:** `-Xms2g -Xmx2g -XX:+UseG1GC`

## Parameters

| Parameter | Value                                          |
|-----------|------------------------------------------------|
| VUs       | 200                                            |
| Duration  | 30s                                            |
| Stock     | 500                                            |
| DB Pool   | 50                                             |
| Strategy  | @Version optimistic lock, no server-side retry |

## Results

| Metric               | Value           |
|----------------------|-----------------|
| Total requests       | 114,781         |
| Orders succeeded     | 500             |
| Lock conflicts (503) | 6,751 (5.9%)    |
| Sold out (409)       | 107,524 (93.7%) |
| Hard errors          | 0               |
| Oversell             | 0               |
| Throughput (total)   | ~3,768 req/s    |
| Latency avg          | 52ms            |
| Latency p(95)        | 180ms           |

## What these numbers show

Correctness held — zero oversell, 500 tickets sold exactly.

The 6,751 lock conflicts happened during the initial burst. With 200 writers on one
row, only one wins per version increment; the rest get 503 and retry. That's the
expected cost of `@Version` under heavy write contention — correct, just expensive.

The bigger problem isn't the conflicts, though. It's the 107,524 sold-out checks
that kept hitting the database after stock was already gone. Once the 500 tickets
sold (in the first few seconds), the system spent the remaining ~28 seconds doing
full DB round-trips just to confirm there was nothing left. The database has no way
to short-circuit that on its own.

Those two problems drove the two fixes: conditional UPDATE killed the lock conflicts,
and the CDC-fed stock query endpoint (`GET /api/v1/ticket-types/{id}/stock`) gives
clients a cheap way to see stock hit 0 before posting an order — pulling the
sold-out storm off the write path entirely.

## Raw Output

```
execution: local
   script: docs/benchmarks/scripts/optimistic-lock-baseline-archived.js
 
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