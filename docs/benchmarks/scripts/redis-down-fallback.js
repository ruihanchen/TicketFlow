// docs/benchmarks/scripts/redis-down-fallback.js
// Benchmark: DB conditional UPDATE fallback when Redis is unavailable.
// proves the system degrades gracefully without Redis.
//
// Expected:
//   lock_conflicts=0  (conditional UPDATE, not @Version)
//   success_rate ~30-40%  (DB row-lock throughput limit, not retry storm)
//   P95 ~300-500ms  (row-lock queue latency)
//   hard_errors=0

import http from 'k6/http';
import { Counter, Trend } from 'k6/metrics';
import { prepareTestData, INITIAL_STOCK } from './fixtures.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const VUS      = parseInt(__ENV.VUS || '200');
const DURATION = __ENV.DURATION || '30s';

export const options = {
    scenarios: {
        flash_sale: {
            executor: 'constant-vus',
            vus: VUS,
            duration: DURATION,
        },
    },
    thresholds: {
        'hard_errors': ['count==0'],
    },
};

const orderSuccess  = new Counter('order_success');
const soldOut       = new Counter('sold_out');
const lockConflicts = new Counter('lock_conflicts');
const hardErrors    = new Counter('hard_errors');
const orderLatency  = new Trend('order_latency', true);

export function setup() {
    // Event creation still works with Redis down, initStock's Redis warmUp, fails silently (caught), DB init succeeds.
    return prepareTestData();
}

export default function (data) {
    const requestId = `${__VU}-${__ITER}-${Date.now()}`;

    const res = http.post(`${BASE_URL}/api/v1/orders`,
        JSON.stringify({
            ticketTypeId: data.ticketTypeId,
            quantity: 1,
            requestId: requestId,
        }),
        {
            headers: {
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${data.userToken}`,
            },
        }
    );

    orderLatency.add(res.timings.duration);

    if (res.status === 201) {
        orderSuccess.add(1);
    } else if (res.status === 409) {
        soldOut.add(1);
    } else if (res.status === 503) {
        // Should be ~0 — conditional UPDATE has no version conflicts
        lockConflicts.add(1);
    } else {
        hardErrors.add(1);
        if (__ITER < 3) {
            console.warn(`[VU ${__VU}] Unexpected: status=${res.status}, body=${res.body}`);
        }
    }
}

export function teardown(data) {
    console.log(`
========== REDIS DOWN FALLBACK RESULTS ==========
Stock: ${INITIAL_STOCK}, VUs: ${VUS}, Duration: ${DURATION}
Strategy: DB conditional UPDATE (Redis unavailable)
 
Check the k6 summary output above for:
  order_success   -- tickets sold (should = stock)
  sold_out        -- rejected via DB conditional UPDATE (affected=0)
  lock_conflicts  -- should be ~0 (conditional UPDATE, not @Version)
  hard_errors     -- should be 0
  order_latency   -- higher than Redis path (all requests hit DB)
 
Remember to restart Redis:  docker start ticketflow-redis
Full analysis: docs/benchmarks/results/redis-down-fallback.md
==============================================================`);
}