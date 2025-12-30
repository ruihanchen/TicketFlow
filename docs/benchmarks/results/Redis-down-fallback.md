# Benchmark: Graceful Degradation (Redis Unavailable)

**Date:** 2025-12-29

## Environment

- **Host OS:** Windows 11 Home (Build 26200)
- **CPU:** Intel Core Ultra 9 275HX (2.70 GHz)
- **RAM:** 32GB @ 6400 MT/s
- **Storage:** 2TB NVMe SSD (High I/O)
- **Runtime:** Docker Engine v25+ (WSL 2 Backend)
- **Resource Limits (Per Service):**
    - **PostgreSQL:** 2.0 vCPUs, 2GB RAM (Cgroups limited)
    - **Redis:** Stopped before test (`docker stop ticketflow-redis`)
    - **TicketFlow App:** 2.0 vCPUs, 2GB RAM (Cgroups limited)
- **JVM Settings:** `-Xms2g -Xmx2g -XX:+UseG1GC`

**Script:** `docs/benchmarks/scripts/redis-down-fallback.js`

## Parameters

| Parameter | Value                                           |
|-----------|-------------------------------------------------|
| VUs       | 200                                             |
| Duration  | 30s                                             |
| Stock     | 500                                             |
| DB Pool   | 50                                              |
| Strategy  | DB conditional UPDATE (Redis container stopped) |

## Results

| Metric                  | Value          |
|-------------------------|----------------|
| Total requests          | 37,329         |
| Orders succeeded        | 500            |
| Sold out (409)          | 36,823 (98.7%) |
| Lock conflicts (503)    | 0              |
| Hard errors             | 0              |
| Oversell                | 0              |
| Throughput (total)      | ~1,226 req/s   |
| Throughput (successful) | ~16 req/s      |
| Latency avg             | 161ms          |
| Latency p(90)           | 246ms          |
| Latency p(95)           | 280ms          |

## Interpretation

500 tickets sold exactly. Zero oversell, zero lock conflicts, zero hard errors.
The system continued operating correctly with Redis unavailable.

Throughput dropped to 1,226 req/s from 2,903 with Redis, because every request
now hits the database including sold-out checks. With Redis, a stock exhaustion
check is a single `GET` in ~0.1ms. Without it, each check requires a DB conditional
UPDATE round-trip of roughly 5 to 10ms, and all 200 VUs are queuing on the same row.

P95 latency of 280ms compared to 79ms with Redis reflects this directly. The database
is handling both successful orders and sold-out checks with no fast path to offload
the latter.

Lock conflicts stayed at zero because the fallback uses conditional UPDATE, not
@Version. Conditional UPDATE is atomic, one SQL per request with no retry. Falling
back to @Version here would replay the retry storm seen in the baseline, multiplying
DB load during an already-degraded state.

## Raw Output

```
execution: local
   script: docs/benchmarks/scripts/redis-down-fallback.js
 
scenarios: (100.00%) 1 scenario, 200 max VUs, 1m0s max duration
         * flash_sale: 200 looping VUs for 30s (gracefulStop: 30s)
 
█ THRESHOLDS
 
    hard_errors
    ✓ 'count==0' count=0
 
 
█ TOTAL RESULTS
 
    CUSTOM
    hard_errors....................: 0      0/s
    order_latency..................: avg=160.91ms min=3.66ms med=152.82ms max=738.71ms p(90)=245.87ms p(95)=280.11ms
    order_success..................: 500    16.415373/s
    sold_out.......................: 36823  1208.92653/s
 
    HTTP
    http_req_duration..............: avg=160.9ms  min=3.66ms med=152.82ms max=738.71ms p(90)=245.87ms p(95)=280.06ms
      { expected_response:true }...: avg=445.65ms min=4.66ms med=469.04ms max=738.71ms p(90)=570.62ms p(95)=600.43ms
    http_req_failed................: 98.64% 36824 out of 37329
    http_reqs......................: 37329  1225.538887/s
 
    EXECUTION
    iteration_duration.............: avg=161ms    min=3.66ms med=152.99ms max=738.71ms p(90)=245.93ms p(95)=280.14ms
    iterations.....................: 37323  1225.341902/s
    vus............................: 200    min=200       max=200
 
    NETWORK
    data_received..................: 15 MB  494 kB/s
    data_sent......................: 15 MB  491 kB/s
 
running (0m30.5s), 000/200 VUs, 37323 complete and 0 interrupted iterations
flash_sale ✓ [======================================] 200 VUs  30s
```
