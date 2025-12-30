// docs/benchmarks/scripts/redis-lua.js
// Benchmark: Redis Lua atomic inventory deduction.
// measures improvement over the @Version baseline (optimistic-lock-baseline.js).
// expected: lock_conflicts=0, success_rate ~99%, P95 <30ms.


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
========== REDIS LUA BENCHMARK RESULTS ==========
Stock: ${INITIAL_STOCK}, VUs: ${VUS}, Duration: ${DURATION}
Strategy: Redis Lua atomic check-and-decrement + DB conditional UPDATE sync
 
Check the k6 summary output above for:
  order_success   -- tickets sold (should = stock)
  sold_out        -- rejected after stock depleted (handled by Redis GET, no DB)
  lock_conflicts  -- should be 0 (no @Version on this path)
  hard_errors     -- should be 0
  order_latency   -- P95 reflects mix of fast rejections + slow successful orders
 
Full analysis: docs/benchmarks/results/redis-lua.md
============================================`);
}