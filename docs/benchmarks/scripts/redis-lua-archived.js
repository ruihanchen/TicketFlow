// docs/benchmarks/scripts/redis-lua-archived.js
//
// ARCHIVED: measures an architecture that was deliberately removed.
//
// Written for the dual-write design: Redis Lua atomic check-and-decrement
// on the write path, followed by a DB conditional UPDATE. That design
// produced good benchmark numbers (2,903 req/s, P95 79ms) but had a
// correctness problem we didn't catch until later: a crash between the
// Redis write and the DB write left the two stores permanently inconsistent,
// with no compensation path that had been tested against a real failure.
//
// The dual-write is gone. Postgres is the only inventory writer now.
// Redis is populated by the Debezium CDC pipeline and serves the read path
// only. Running this script today measures a DB-only write path, the
// numbers will look like redis-down-fallback-archived.js, not like the
// historical Redis-lua-archived.md results.
//
// See Redis-lua-archived.md for the full post-mortem.

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
    console.warn('=== ARCHIVED SCRIPT: redis-lua-archived.js ===');
    console.warn('The Redis Lua write path is gone. This measures DB-only now.');
    console.warn('Historical numbers are in Redis-lua-archived.md.');
    console.warn('==============================================');
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
ARCHIVED SCRIPT, results below reflect the current DB-only write path,
not the historical Redis Lua numbers (2,903 req/s, P95 79ms).
See Redis-lua-archived.md for why that architecture was pulled.
 
Stock: ${INITIAL_STOCK}  VUs: ${VUS}  Duration: ${DURATION}
`);
}