// docs/benchmarks/scripts/flash-sale-spike.js
//
// Proves write-path correctness under an instant demand spike.
// Uses ramping-arrival-rate (open model): iterations fire regardless of response time,
// simulating real users hammering "buy" at sale open. constant-vus masks spike behavior.

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.1.0/index.js';
import { prepareTestData, INITIAL_STOCK, BASE_URL } from './fixtures.js';
import { scrapePrometheus, fetchFinalStock } from './helpers/prometheus.js';

const PEAK_RPS = parseInt(__ENV.PEAK_RPS || '150');
const BASE_RPS = parseInt(__ENV.BASE_RPS || '10');

export const options = {
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
    scenarios: {
        flash_sale: {
            executor:        'ramping-arrival-rate',
            startRate:       BASE_RPS,
            timeUnit:        '1s',
            preAllocatedVUs: Math.ceil(PEAK_RPS * 0.3),
            maxVUs:          PEAK_RPS * 3,
            stages: [
                { duration: '60s', target: BASE_RPS },   // baseline: confirm healthy
                { duration: '0s',  target: PEAK_RPS },   // spike: instant jump
                { duration: '90s', target: PEAK_RPS },   // sustain: stock drains
                { duration: '30s', target: BASE_RPS },   // recovery: no latency tail
            ],
        },
    },
    thresholds: {
        'hard_errors':      ['count==0'],
        'write_latency':    ['p(95)<300', 'p(99)<600'],
        'soldout_latency':  ['p(95)<200', 'p(99)<400'],
    },
};

// metrics
const writeLatency   = new Trend('write_latency', true);
const soldoutLatency = new Trend('soldout_latency', true);
const hardErrors     = new Counter('hard_errors');
const orderSuccess   = new Counter('order_success');
const soldOut        = new Counter('sold_out');

// accumulators for teardown latency split
let writeSamples   = { count: 0, sum: 0, p95: 0 };
let soldoutSamples = { count: 0, sum: 0, p95: 0 };

// setup
export function setup() {
    const data = prepareTestData();
    const drainTime = Math.ceil(INITIAL_STOCK / PEAK_RPS);
    console.log(`[setup] stock=${INITIAL_STOCK}, peakRPS=${PEAK_RPS}, estimated drain ~${drainTime}s`);
    return data;
}

// VU function
export default function (data) {
    const requestId = `spike-${__VU}-${__ITER}-${Date.now()}`;

    const res = http.post(`${BASE_URL}/api/v1/orders`,
        JSON.stringify({ ticketTypeId: data.ticketTypeId, quantity: 1, requestId }),
        {
            headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${data.userToken}` },
            tags: { op: 'write_order' },
        }
    );

    let body;
    try { body = JSON.parse(res.body); } catch (_) {
        hardErrors.add(1);
        return;
    }

    if (res.status === 201) {
        orderSuccess.add(1);
        writeLatency.add(res.timings.duration);
        check(res, {
            'created 201':    (r) => r.status === 201,
            'has orderNo':    () => !!body?.data?.orderNo,
            'status CREATED': () => body?.data?.status === 'CREATED',
        });
    } else if (res.status === 409 && body?.code === 2001) {
        soldOut.add(1);
        soldoutLatency.add(res.timings.duration);
    } else {
        hardErrors.add(1);
    }
    // no sleep: arrival-rate executor controls throughput externally
}

// teardown: oversell audit + latency split
export function teardown(data) {
    const prom   = scrapePrometheus(BASE_URL);
    const final_ = fetchFinalStock(BASE_URL, data.ticketTypeId);

    const remaining = final_ ? final_.stock : null;
    const report = {
        oversellAudit: {
            initialStock: INITIAL_STOCK,
            remaining:    remaining,
            expectedSold: remaining !== null ? INITIAL_STOCK - remaining : null,
            invariant:    'verify: order_success (k6 summary) + remaining == INITIAL_STOCK',
            verdict:      remaining === 0 ? 'ALL_SOLD' : remaining > 0 ? 'PARTIAL' : 'UNKNOWN',
        },
        // successful writes are slower than rejections: full INSERT + state machine vs.
        // guardDeduct exiting on affected=0 with no INSERT. see k6 summary for numbers.
        latencySplit: {
            successfulWrite_p95_ms:  'see write_latency p(95) in k6 summary',
            soldoutRejection_p95_ms: 'see soldout_latency p(95) in k6 summary',
        },
        prometheus: prom,
    };

    console.log(`\n[teardown] ${JSON.stringify(report, null, 2)}`);
}

// summary
export function handleSummary(data) {
    return {
        stdout: textSummary(data, { indent: '  ', enableColors: true }),
        'docs/benchmarks/results/flash-sale-spike.json': JSON.stringify(data, null, 2),
    };
}