# Archived: Graceful Degradation (Redis Unavailable)

**Date:** 2025-12-29
**Status:** Current — characterizes the production write path
**Script:** `docs/benchmarks/scripts/redis-down-fallback-archived.js`

`POST /api/v1/orders` flows through `InventoryAdapter.deductStock()`, which
delegates to `InventoryService.guardDeduct()` — a single Postgres conditional
UPDATE, no retry:

```sql
UPDATE inventories
   SET available_stock = available_stock - :quantity
 WHERE ticket_type_id  = :ticketTypeId
   AND available_stock >= :quantity
```

Redis has no role on this path. The canonical run was captured with Redis
stopped (`docker stop ticketflow-redis`) to make that statement directly.
Running with Redis up produces the same numbers.

## Environment

- **OS:** Windows 11 Home (Build 26200)
- **CPU:** Intel Core Ultra 9 275HX (2.70 GHz)
- **RAM:** 32 GB @ 6400 MT/s
- **Storage:** 2 TB NVMe SSD
- **Runtime:** Docker Engine v25+ (WSL 2)
- **Resource limits:** PostgreSQL 2.0 vCPU / 2 GB, App 2.0 vCPU / 2 GB, Redis stopped
- **JVM:** `-Xms2g -Xmx2g -XX:+UseG1GC`

## Parameters

| Parameter | Value                                           |
|-----------|-------------------------------------------------|
| VUs       | 200                                             |
| Duration  | 30s                                             |
| Stock     | 500                                             |
| DB Pool   | 50                                              |
| Strategy  | DB conditional UPDATE (`guardDeduct`), no retry |

## Results

| Metric               | Value          |
|----------------------|----------------|
| Total requests       | 37,329         |
| Orders succeeded     | 500            |
| Sold out (409)       | 36,823 (98.7%) |
| Lock conflicts (503) | 0              |
| Hard errors          | 0              |
| Oversell             | 0              |
| Throughput (total)   | ~1,226 req/s   |
| Latency avg          | 161ms          |
| Latency p(95)        | 280ms          |

## Interpretation

500 tickets sold exactly. Zero oversell, zero lock conflicts, zero hard errors.
The write path is correct under 200 concurrent writers on a single hot row.

### Why zero lock conflicts

Conditional UPDATE acquires a row-level lock, evaluates
`available_stock >= :quantity` inside that lock, and either writes or returns
zero affected rows. There's no version number to check, no retry loop, no 503
path. Every request either sells a ticket or gets a clean 409.

### Why P95 is 280ms

Stock depletes in the first second or two. After that, every request still has
to acquire the row-level lock, run the UPDATE, observe zero affected rows, and
return 409. With 200 VUs queued behind the same row, lock queue depth drives
the latency — not the work itself.

This is the honest cost of having no read-side short-circuit. The CDC-fed stock
query endpoint (`GET /api/v1/ticket-types/{id}/stock`) exists for exactly this
reason: clients poll Redis to check stock before posting an order, so the
write path only sees requests that have a real shot at succeeding.

### Comparison with the archived Redis Lua numbers

The Redis Lua design hit 2,903 req/s vs. 1,226 req/s here — a real 2.4× gap.
That gap came from answering sold-out checks in Redis memory instead of the DB.
The current architecture moves that same benefit to the read side: the stock
query endpoint serves from Redis at sub-ms latency, so the 36,823 sold-out
attempts in this benchmark shouldn't reach the write path in production at all.

## Raw Output

```
execution: local
   script: docs/benchmarks/scripts/redis-down-fallback-archived.js
 
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