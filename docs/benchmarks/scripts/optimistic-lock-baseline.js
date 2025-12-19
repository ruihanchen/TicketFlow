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
    //VU + iteration + timestamp ensures uniqueness across all concurrent VUs
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
        //stock depleted or idempotency hit
        soldOut.add(1);
    } else if (res.status === 503) {
        //optimistic lock conflict (@Version)
        lockConflicts.add(1);
    } else {
        hardErrors.add(1);
        if (__ITER < 3) {
            // cap log volume:200 VUs * N iterations would flood the console
            console.warn(`[VU ${__VU}] Unexpected: status=${res.status}, body=${res.body}`);
        }
    }
}

export function teardown(data) {
    console.log(`
========== BASELINE RESULTS: @Version Optimistic Locking ==========
Stock: ${INITIAL_STOCK}, VUs: ${VUS}, Duration: ${DURATION}
Strategy: @Version optimistic lock, no server side retry

Check the k6 summary output above for:
  order_success   -- tickets sold
  lock_conflicts  -- @Version rejections (503); high under contention, expected
  sold_out        -- requests after stock depleted (409)
  hard_errors     -- must be 0
  order_latency   -- p(90/95/99) reflects contention cost

Cleanup before next run:
  psql -U postgres -d ticketflow -f docs/benchmarks/scripts/k6-cleanup.sql
============================================================
`);
}