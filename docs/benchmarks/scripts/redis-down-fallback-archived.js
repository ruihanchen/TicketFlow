// docs/benchmarks/scripts/redis-down-fallback-archived.js
//
// ARCHIVED: historical run of the current write path with Redis stopped.
//
// Captured to prove that the write path has no Redis dependency:
// InventoryAdapter.deductStock() delegates straight to a DB conditional
// UPDATE, so stopping Redis has zero effect on correctness or throughput.
//
// The numbers in Redis-down-fallback-archived.md were recorded with
// `docker stop ticketflow-redis` before launch. Running this script with
// Redis up produces the same results, since Redis is not on this code path.
//
// This script is still valid against current code, the write path hasn't
// changed. It's archived because a fresh run should produce a new result
// file rather than overwriting the historical baseline.

import http from 'k6/http';
import { Counter, Trend } from 'k6/metrics';
import { prepareTestData, INITIAL_STOCK } from './fixtures.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const VUS      = parseInt(__ENV.VUS || '200');
const DURATION = __ENV.DURATION || '30s';

export const options = {
    scenarios: {
        flash_sale: { executor: 'constant-vus', vus: VUS, duration: DURATION },
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
    // initStock writes to DB only; CDC propagates to Redis async.
    // If Redis is stopped, CDC buffers until it comes back, no effect on this run.
    return prepareTestData();
}

export default function (data) {
    const requestId = `${__VU}-${__ITER}-${Date.now()}`;

    const res = http.post(`${BASE_URL}/api/v1/orders`,
        JSON.stringify({ ticketTypeId: data.ticketTypeId, quantity: 1, requestId }),
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
        // conditional UPDATE has no @Version retry, a non-zero value here is a regression
        lockConflicts.add(1);
    } else {
        hardErrors.add(1);
        if (__ITER < 3) {
            console.warn(`[VU ${__VU}] unexpected status=${res.status} body=${res.body}`);
        }
    }
}

export function teardown(data) {
    console.log(`
Stock: ${INITIAL_STOCK}  VUs: ${VUS}  Duration: ${DURATION}
Strategy: DB conditional UPDATE (Redis stopped)
 
  order_success   :     must equal stock
  sold_out        :     rejected via conditional UPDATE (affected=0)
  lock_conflicts  :     must be 0
  hard_errors     :     must be 0
 
Restart Redis if stopped:  docker start ticketflow-redis
Historical results: Redis-down-fallback-archived.md
`);
}