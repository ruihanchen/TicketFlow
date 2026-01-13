// docs/benchmarks/scripts/optimistic-lock-baseline-archived.js
//
// ARCHIVED: does not measure the current write path.
//
// Written for the @Version optimistic locking architecture. At the time,
// POST /api/v1/orders went through InventoryService.deductStock(), which
// used JPA @Version and returned 503 on OptimisticLockingFailureException.
//
// That path no longer exists. The current write path uses a conditional
// UPDATE in InventoryAdapter.deductStock() and never returns a lock conflict.
// Running this script today will show lock_conflicts=0, the 503 path is
// unreachable, not broken.
//
// For the current write path: use redis-down-fallback-archived.js (same
// code path, different conditions) or re-run after any write-path changes.

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
    console.warn('=== ARCHIVED SCRIPT: optimistic-lock-baseline-archived.js ===');
    console.warn('The @Version write path is gone. lock_conflicts will be 0.');
    console.warn('See Optimistic-lock-baseline-archived.md for historical numbers.');
    console.warn('=============================================================');
    return prepareTestData();
}

export default function (data) {
    // VU + iteration + timestamp guarantees uniqueness across concurrent VUs
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
        // @Version conflict, unreachable on current code
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
ARCHIVED SCRIPT: results below reflect current code, not the @Version baseline.
Historical numbers (6,751 lock conflicts, P95 180ms) are in Optimistic-lock-baseline-archived.md.
 
Stock: ${INITIAL_STOCK}  VUs: ${VUS}  Duration: ${DURATION}
`);
}