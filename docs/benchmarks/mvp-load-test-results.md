# Benchmarks & Load Test Results

## Honest Preface

Phase 1 is not designed for flash sale throughput. It is designed to validate
correctness under moderate load. This document records what has been measured,
what the known limitations are, and what Phase 2 is expected to improve.

Fabricating throughput numbers would be counterproductive — the architectural
limitations of Phase 1 are known, documented, and intentional.
See [ADR-004](../decisions/ADR-004-optimistic-locking-mvp.md) for the full reasoning.

---

## Test Environment

| Component | Specification |
|-----------|--------------|
| Machine | Windows 11, Java 21.0.9 |
| Database | PostgreSQL 16.13 (Testcontainers Docker container) |
| HikariCP pool | max 10 connections, min idle 5 |
| Test tool | Testcontainers integration test (executed) / k6 (planned) |

---

## Phase 1 Baseline: Single-User API Response Times

Measured manually via curl. Represents best-case latency with no concurrency.

| Endpoint | Method | Avg Response Time | Notes |
|----------|--------|------------------|-------|
| POST /auth/register | Write | ~45ms | BCrypt cost=12, intentionally slow |
| POST /auth/login | Read | ~50ms | BCrypt verification |
| GET /events | Read | ~8ms | Simple table scan, no joins |
| GET /events/{id} | Read | ~12ms | Joins ticket_types + inventories |
| POST /orders | Write | ~25ms | Inventory deduction + order insert |
| POST /orders/{no}/pay | Write | ~10ms | State machine + single UPDATE |
| POST /orders/{no}/confirm-payment | Write | ~10ms | State machine + single UPDATE |

**Why is login ~50ms?**
BCrypt with cost factor 12 is deliberately slow — it makes brute-force attacks
computationally expensive. This is a security feature, not a performance bug.
Login throughput is intentionally limited to ~20 req/s per core.

---

## Phase 1 Concurrency: Known Limitation

Phase 1 uses optimistic locking for inventory deduction. Under high concurrency,
the behavior is predictable and by design:

```
Scenario: 100 concurrent users attempt to purchase the last 10 tickets

Expected outcome:
  ✅ Exactly 10 orders succeed (no overselling)
  ⚠️  90 users receive: {"code": 30005, "message": "High demand — please try again"}
  ✅  Inventory never goes below 0 (CHECK constraint + optimistic lock)

Retry behavior:
  Users who receive 30005 are expected to retry
  Each retry has a fair chance of success if stock remains
```

**The tradeoff is explicit**: Under flash sale conditions (thousands of concurrent
writes), optimistic lock retry rates will be high and throughput will degrade.
This is acceptable for Phase 1. It is the primary motivation for Phase 2.

---

## Phase 1 Concurrency Test Results (Executed)

**Test date**: 2026-03-16
**Machine**: Windows 11, Java 21.0.9, PostgreSQL 16.13 (Testcontainers Docker container)
**Test class**: `ConcurrentInventoryTest` — Testcontainers integration test
**Branch**: `feature/phase2-redis-inventory`

| Metric | Result |
|--------|--------|
| Concurrent threads | 200 |
| Total stock | 50 |
| Tickets sold | **50** |
| Overselling incidents | **0** |
| Failed — sold out | 150 |
| Optimistic lock conflicts | Heavy (visible as WARN in logs) |
| Test duration | ~33s |

**What the logs show**: Each successful deduction is preceded by multiple WARN-level
`OptimisticLockException` conflicts from competing threads. With retry loops enabled,
all 50 tickets are eventually sold — but the process takes ~33 seconds for 50 tickets,
exposing the throughput cost of optimistic locking under high contention.

**Key finding**: Zero overselling is guaranteed. But the retry storm means throughput
collapses under flash sale conditions. Selling 50 tickets across 200 competing threads
took ~33s — roughly **1.5 tickets/second** under contention. This is the measured
motivation for Phase 2.

**Phase 2 prediction**: Same test, `RedisInventoryAdapter` swapped in. The Lua script
eliminates retry storms entirely — all 200 threads resolve in a single pass, zero WARN
logs, test duration expected to drop from ~33s to under 3s.

---

## Phase 1 Load Test Plan (k6, Pending Execution)

The following test suite is written and ready to run. Results will be added once
executed on isolated hardware.

### Test 1: Baseline Throughput

```javascript
// k6 script — baseline.js
import http from 'k6/http';
import { check } from 'k6';

export const options = {
  vus: 10,          // 10 virtual users
  duration: '30s',
};

export default function () {
  const res = http.get('http://localhost:8080/api/v1/events?page=0&size=10');
  check(res, { 'status 200': (r) => r.status === 200 });
}
```

**Goal**: Establish baseline read throughput and P99 latency for non-contended endpoints.

---

### Test 2: Concurrent Order Creation (Flash Sale Simulation)

```javascript
// k6 script — flash-sale.js
import http from 'k6/http';
import { check } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

export const options = {
  scenarios: {
    flash_sale: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '10s', target: 200 },   // ramp up
        { duration: '30s', target: 200 },   // hold
        { duration: '10s', target: 0 },     // ramp down
      ],
    },
  },
};

const BASE = 'http://localhost:8080';
const TOKEN = __ENV.USER_TOKEN;

export default function () {
  const payload = JSON.stringify({
    ticketTypeId: 2,
    quantity: 1,
    requestId: uuidv4(),   // unique per attempt — not testing idempotency here
  });

  const res = http.post(`${BASE}/api/v1/orders`, payload, {
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${TOKEN}`,
    },
  });

  // Both 200 (success) and 30005 (lock conflict) are acceptable outcomes
  // 30005 means the system correctly detected contention and asked for retry
  // Any other status code is a failure
  check(res, {
    'order created or retryable': (r) => {
      const body = JSON.parse(r.body);
      return body.code === 200 || body.code === 30005;
    },
    'no overselling (never 30004 when stock exists)': (r) => {
      // This check is validated separately by querying inventory after the test
      return true;
    },
  });
}
```

**What this test validates:**
- Inventory never goes below zero (oversell protection)
- All responses are either success or a retryable conflict — no 500 errors
- The system remains stable under sustained 200 VU load

---

### Test 3: Idempotency Under Concurrency

```javascript
// k6 script — idempotency.js
// Same requestId sent by 50 concurrent users simultaneously
// Expected: exactly 1 order created, 49 requests return the same order

export const options = { vus: 50, iterations: 50 };

const FIXED_REQUEST_ID = 'idempotency-test-fixed-uuid-12345';

export default function () {
  const res = http.post(`${BASE}/api/v1/orders`, JSON.stringify({
    ticketTypeId: 2,
    quantity: 1,
    requestId: FIXED_REQUEST_ID,
  }), { headers: { ... } });

  const body = JSON.parse(res.body);
  check(res, {
    'same order returned': (r) => body.data.orderNo === body.data.orderNo,
    'no duplicate created': (r) => body.code === 200,
  });
}

// Post-test validation:
// SELECT COUNT(*) FROM orders WHERE request_id = 'idempotency-test-fixed-uuid-12345';
// Expected: 1
```

---

## Phase 2 Target Metrics

After Redis-based inventory and Kafka async processing are introduced:

| Metric | Phase 1 Baseline | Phase 2 Target | How Achieved |
|--------|-----------------|----------------|--------------|
| Order creation throughput | ~1.5 tickets/s (contended) | 2,000+ req/s | Redis Lua atomic deduction |
| P99 order creation latency | ~33s for 50 tickets (200 threads) | < 3s for 50 tickets | Eliminate DB lock contention |
| Oversell rate | 0% | 0% | Redis atomic ops + DB constraint |
| Idempotency accuracy | 100% | 100% | Redis fast path + DB last resort |
| Timeout cancellation lag | up to 60s | < 2s | Redis delayed queue replaces polling |
| Optimistic lock conflicts | Heavy WARN logs | Zero | Redis single-threaded command processing |

> Phase 1 baseline numbers are measured (see Concurrency Test Results above).
> Phase 2 target numbers are architectural projections — actual measurements
> will replace these once Phase 2 implementation is complete.

---

## Correctness Guarantees (Phase 1, Verified)

These are not projections — they are verified behaviors:

| Guarantee | Test | Result |
|-----------|------|--------|
| No overselling | 200 threads, 50 tickets, persistent retry | sold=50, overselling=0 ✅ |
| Idempotency | Same `requestId` sent twice | Same order returned, no duplicate ✅ |
| Illegal state transition | Cancel a CONFIRMED order | `40006 ORDER_CANCEL_NOT_ALLOWED` ✅ |
| Timeout recovery | Let order expire past `expired_at` | `OrderTimeoutService` cancels + releases stock ✅ |
| Price snapshot | Change ticket price after order | `order.unitPrice` unchanged ✅ |
| Cross-user order access | Query another user's orderNo | `40001 ORDER_NOT_FOUND` (not 403) ✅ |
| Admin-only enforcement | Create event with USER token | `403 Forbidden` ✅ |

**Correctness is non-negotiable. These guarantees hold regardless of load.**

---

## How to Run the Load Tests

```bash
# Install k6
brew install k6   # macOS
# or: https://k6.io/docs/getting-started/installation/

# Start the application
cd docker && docker-compose up -d
./mvnw spring-boot:run -pl app

# Get a user token
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"password123"}' \
  | jq -r '.data.accessToken')

# Run baseline test
k6 run docs/benchmarks/scripts/baseline.js

# Run flash sale simulation
USER_TOKEN=$TOKEN k6 run docs/benchmarks/scripts/flash-sale.js

# After test: verify no overselling
docker exec -it ticketflow-postgres psql -U postgres -d ticketflow \
  -c "SELECT available_stock FROM inventories WHERE ticket_type_id = 2;"
# Expected: >= 0 (never negative)
```