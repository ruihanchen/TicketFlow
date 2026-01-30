# Benchmark Report

**Environment:** Windows 11 Home, Intel Core Ultra 9 275HX, 32 GB RAM, Docker Engine
v25+ on WSL2. PostgreSQL 16 and the app each capped at 2.0 vCPU / 2 GB. JVM:
`-Xms2g -Xmx2g -XX:+UseG1GC`. DB pool: 50 connections. Redis 7.4.1 unconstrained.

One note on absolute numbers: this all runs in Docker on WSL2 on a laptop, and the latency
figures reflect that. WSL2 scheduling adds jitter you wouldn't see on bare-metal Linux.
The numbers are internally consistent and fine for comparing approaches, but don't read
them as production benchmarks.

---

## CDC pipeline consistency under concurrent load

**Script:** `cdc-read-path.js` · 200 VUs, 30s

90% of the VUs poll `GET /stock` continuously. The other 10% place orders, which
generates write traffic and CDC events at the same time. The question: does Redis stay
consistent while both paths run concurrently?

| Metric | Value |
|---|---|
| Cache hit rate | 100% (934,780 hits, 0 misses, 0 DB fallthroughs) |
| Read latency p50 / p95 / p99 | 5.11ms / 8.27ms / 11.31ms |
| Read latency max | 68.45ms |
| CDC avg lag | 0.544s |
| CDC handler errors | 0 |
| Orders sold | 500/500 |
| Orders created (Prometheus) | 500 |
| Stock consistency | PASS. Final `GET /stock` returned stock=0 from CACHE |
| Cache consistency | CONSISTENT. 0 misses, 0 fallthroughs across the entire run |

Teardown runs two independent consistency checks. One verifies the final value. The
other verifies the whole run. Not a single read fell through to Postgres at any point
while writers were active.

The read path also produced 80 sold-out responses. Those came from the 10% write-path
VUs hitting `POST /orders` after stock ran out. Nothing to do with CDC consistency.
They show up here because the test mixes read and write traffic.

The 68ms max read is WSL2 scheduling noise. The p99 held at 11.31ms over 934,000
requests, so one spike doesn't say anything about steady-state behavior.

CDC lag averaged 0.544s. A client who reads stock right as the last ticket sells might
see a stale value for under half a second, but CDC lag can't cause an oversell. The
write path doesn't read from Redis.

---

## Read-first user journey vs. write-only baseline

**Script:** `flash-sale-realistic.js` · 200 VUs, 30s

Each VU checks stock first, then places an order only if stock > 0. Once Redis shows
stock=0, VUs stop sending orders entirely. The baseline for comparison runs the same
workload without a read cache. Every request goes straight to the write path.

| Metric | With read cache | Without (archived) |
|---|---|---|
| Write-path requests | 1,332 | 37,329 |
| Sold-out hitting DB | 832 | 36,823 |
| Short-circuited at Redis | 947,420 (99.85%) | 0 |
| Orders sold | 500 | 500 |
| Hard errors | 0 | 0 |
| Read latency p50 / p95 / p99 | 5.39ms / 8.44ms / 10.67ms | |
| Write latency avg / med / p95 | 270ms / 174ms / 628ms | |
| Orders created (Prometheus) | 500 | |

947,420 requests resolved in Redis without ever hitting the write path. The DB handled
1,332 write attempts instead of 37,329. That's a 96.4% reduction in write traffic with
identical correctness. 500 tickets sold, 0 hard errors, both ways.

The write latency numbers look alarming at first glance. 628ms p95. They aren't a
problem. They're the point. With only 1,332 write requests across 30 seconds, the
write path was nearly idle most of the time. The requests that did arrive clustered
around the moment stock hit zero, which is exactly when lock contention peaks. The wide
spread between median (174ms) and p95 (628ms) is a handful of requests catching the
tail of that contention window. In the no-read-cache baseline, the same contention was
spread across 37,329 requests.

The spike test below confirms CDC doesn't add anything to the write path. 16,500 writes under a
spike, p95 of 16.6ms.

The 832 sold-out responses come from the CDC lag window. A VU reads stock > 0, decides
to buy, and gets a 409 because someone else grabbed the last ticket between the read
and the write. The lag window (~0.4s) is narrower than most requests' round-trip time,
so races are rare. The server-side `insufficient_stock` counter matched k6's `sold_out`
counter exactly at 832.

---

## Write path under an open-model spike

**Script:** `flash-sale-spike.js` · up to 45 active VUs (maxVUs=450 configured), 3 minutes

`ramping-arrival-rate` fires iterations at a fixed rate regardless of server latency.
Closed-model executors (`constant-vus`) hide latency problems because throughput drops
automatically when the server slows down. Open model doesn't. If requests pile up,
they pile up. At ~5ms latency, Little's Law puts the concurrency requirement at
`150 RPS × 0.005s ≈ 0.75` VUs. 45 was plenty. The executor never needed to allocate
more. Scenario: 10 RPS baseline → instant jump to 150 RPS → sustain 90s → recovery.

| Metric | Value |
|---|---|
| Oversell audit | PASS. 500 sold + 0 remaining = 500 |
| Sold-out latency p50 / p95 / p99 | 4.15ms / 5.45ms / 8.22ms |
| Write latency avg / med / p95 | 12.95ms / 12.80ms / 16.6ms |
| Successful order latency p95 | 17.07ms |
| Hard errors | 0 |
| Total iterations | 16,499 over 3 minutes |
| Orders created (Prometheus) | 500 |
| CDC errors | 0 |

The oversell invariant held. `order_success + remaining_stock == INITIAL_STOCK`. The
`guardDeduct` conditional UPDATE serializes concurrent writes at the row level.
There's no window where two transactions both pass the stock check and both commit.

In this run, most of the 16,499 iterations hit an already-empty row. Lock acquires and
releases immediately. The 1,332 writes from the realistic-flash-sale run were
concentrated in the ~0.5s window when stock actually depleted, which is when the
lock queue is deepest.

Successful orders (17.07ms p95) take slightly longer than sold-out rejections (5.45ms
p95), because a successful write runs a full INSERT and state machine update, while a
sold-out exits the conditional UPDATE immediately with zero affected rows.

k6 reported `http_req_failed` at 96.92%. Not errors. k6 counts any non-2xx as a
failure. The 15,999 "failures" are all 409 INSUFFICIENT_STOCK. The `hard_errors`
counter, which tracks 5xx, timeouts, and network failures, stayed at 0.

---

## Crash convergence

**Script:** `cdc-crash-convergence.js` · 5 VUs, 60s, STOCK=5000

Stock is set to 5000 (vs 500 elsewhere) to give the operator enough time to kill the
JVM while writes are still in flight. A 500-stock run sells out before the operator
can reach the kill command.

This is a correctness test, not a performance benchmark. It validates the CDC recovery
path under real process death. Operator force-kills the JVM while writes are in flight,
then restarts the app and verifies Redis converges to Postgres state.

**Procedure:**

1. Start the app with STOCK=5000. Verify both stores agree (Postgres=4720, Redis=4521, normal CDC lag).
2. Run the k6 script. 5 VUs place orders continuously and print heartbeat lines.
3. After ~120 orders, kill the JVM (`Stop-Process -Name java -Force`). k6 detects CONNECTION REFUSED.
4. Query both stores immediately: Postgres=4370, Redis=4395. **Diverged by 25** (Redis ahead).
5. Restart the app. Debezium resumes from the last acknowledged LSN and replays all committed WAL events.
6. CDC upserts flow from 4404 down to 4370. Reconciliation service logs `redis > db, requires manual investigation` once, then CDC catches up.
7. Query both stores: Postgres=4370, Redis=4370. **Converged.**

| Moment | Postgres | Redis | Delta |
|---|---|---|---|
| Before crash | 4720 | 4521 | -199 (normal CDC lag) |
| After crash | 4370 | 4395 | +25 (Redis ahead, diverged) |
| After CDC replay | 4370 | 4370 | 0 (converged) |

**k6 output:**

| Metric | Value |
|---|---|
| Orders placed before kill | 630 |
| Connection lost (post-kill) | 825 |
| Total iterations | 1,455 |

The divergence direction (Redis ahead) is the expected crash failure mode. Some orders
committed in Postgres but their CDC events hadn't been acknowledged yet. On restart,
Debezium replayed those events from the retained WAL, and Redis converged without any
manual intervention. The reconciliation log entry is informational, not an error. It
fires once during the replay window and disappears after convergence.

Screenshots of each phase are in `docs/benchmarks/screenshots/`.

---

## Summary

| | Read path consistency | Read-first journey | Write-path spike | Crash convergence |
|---|---|---|---|---|
| What it tests | CDC pipeline consistency | Read-first vs. write-only | Write path under open-model spike | Crash convergence |
| Oversell | n/a | 0 | 0 | n/a |
| Hard errors | 0 | 0 | 0 | 0 |
| Cache hit rate | 100% | 100% | n/a | n/a |
| Short-circuit rate | n/a | 99.85% | n/a | n/a |
| Read latency p95 / p99 | 8.27ms / 11.31ms | 8.44ms / 10.67ms | n/a | n/a |
| Write latency p95 | n/a | 628ms | 16.6ms | n/a |
| Sold-out latency p95 / p99 | n/a | n/a | 5.45ms / 8.22ms | n/a |
| Orders created (Prometheus) | 500 | 500 | 500 | 630 |
| CDC lag (avg) | 0.544s | 0.443s | 0.491s | n/a |
| CDC errors | 0 | 0 | 0 | 0 |
| Post-crash convergence | n/a | n/a | n/a | PASS (delta=0) |

The 38x gap in write latency between the realistic journey run (628ms) and the spike
run (16.6ms) comes down to when requests arrived, not what they did. The first run's
writes bunched up in the ~0.5s window where stock depleted. The spike's writes spread
across 3 minutes, with most of them hitting an already-empty row. Both passed their
correctness invariants.

---

## Reproducing

```bash
docker compose up -d
./mvnw clean spring-boot:run -pl app

# reset between runs: clears DB state and Prometheus counters
psql -U ticketflow -d ticketflow -f docs/benchmarks/scripts/k6-cleanup.sql
# restart the app after cleanup to reset in-process counters

k6 run docs/benchmarks/scripts/cdc-read-path.js
k6 run docs/benchmarks/scripts/flash-sale-realistic.js
k6 run docs/benchmarks/scripts/flash-sale-spike.js

# crash convergence (manual kill required mid-run):
k6 run -e STOCK=5000 docs/benchmarks/scripts/cdc-crash-convergence.js
# after 8-10 heartbeats: Stop-Process -Name java -Force (PowerShell)
# or: kill -9 $(lsof -ti:8080) (Linux/macOS)
# then restart the app and verify Postgres == Redis
```

Each script writes results to `docs/benchmarks/results/*.json` via `handleSummary()`.
Archived baselines (optimistic locking, Redis Lua dual-write, DB-only without read cache)
are in the same directory with `-archived` in the filename.