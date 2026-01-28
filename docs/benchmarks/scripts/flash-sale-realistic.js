// docs/benchmarks/scripts/flash-sale-realistic.js
//
// Proves CDC read-first-then-write eliminates the sold-out storm.
// Each VU checks stock (Redis) first; if 0, skips the write entirely.
// Comparison baseline: redis-down-fallback ran 37,329 writes, 36,823 hitting DB.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.1.0/index.js';
import { prepareTestData, INITIAL_STOCK, BASE_URL } from './fixtures.js';
import { scrapePrometheus, fetchFinalStock } from './helpers/prometheus.js';

const VUS        = parseInt(__ENV.VUS || '200');
const DURATION   = __ENV.DURATION    || '30s';
const CDC_WARMUP = parseInt(__ENV.CDC_WARMUP || '3');

export const options = {
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
    scenarios: {
        flash_sale: { executor: 'constant-vus', vus: VUS, duration: DURATION },
    },
    thresholds: {
        'hard_errors':        ['count==0'],
        'read_latency':       ['p(95)<50', 'p(99)<100'],
        'short_circuit_rate': ['rate>0.80'],
        // loose ceiling — printed so the number is visible in terminal output
        'write_latency':      ['p(95)<2000', 'p(99)<3000'],
    },
};

// metrics
const readLatency      = new Trend('read_latency', true);
const writeLatency     = new Trend('write_latency', true);
const cacheHitRate     = new Rate('cache_hit_rate');
const orderSuccess     = new Counter('order_success');
const soldOut          = new Counter('sold_out');
const shortCircuited   = new Counter('short_circuited');
const shortCircuitRate = new Rate('short_circuit_rate');
const ordersAttempted  = new Counter('orders_attempted');
const hardErrors       = new Counter('hard_errors');

// setup
export function setup() {
    const data = prepareTestData();

    console.log(`[setup] waiting ${CDC_WARMUP}s for CDC warm-up`);
    sleep(CDC_WARMUP);

    const verify = fetchFinalStock(BASE_URL, data.ticketTypeId);
    console.log(`[setup] stock=${verify ? verify.stock : '?'}, source=${verify ? verify.source : '?'}`);

    return data;
}

// VU function: realistic user journey
export default function (data) {
    // Step 1: check stock (read path — Redis via CDC)
    const stockRes = http.get(
        `${BASE_URL}/api/v1/ticket-types/${data.ticketTypeId}/stock`,
        { tags: { op: 'read_stock' } }
    );
    readLatency.add(stockRes.timings.duration);

    if (stockRes.status !== 200) { hardErrors.add(1); return; }

    const stockBody = JSON.parse(stockRes.body);
    const available = stockBody.data?.availableStock || 0;
    cacheHitRate.add(stockBody.data?.source === 'CACHE');

    // Step 2: decide — skip write if stock is gone
    if (available <= 0) {
        shortCircuited.add(1);
        shortCircuitRate.add(true);
        return;
    }

    // Step 3: attempt purchase
    shortCircuitRate.add(false);
    ordersAttempted.add(1);

    const requestId = `realistic-${__VU}-${__ITER}`;
    const orderRes = http.post(`${BASE_URL}/api/v1/orders`,
        JSON.stringify({ ticketTypeId: data.ticketTypeId, quantity: 1, requestId }),
        {
            headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${data.userToken}` },
            tags: { op: 'write_order' },
        }
    );
    writeLatency.add(orderRes.timings.duration);

    if (orderRes.status === 201) {
        orderSuccess.add(1);
    } else {
        const code = JSON.parse(orderRes.body)?.code;
        // 2001 = race between read and write (CDC lag window); expected, not an error
        if (code === 2001) soldOut.add(1);
        else hardErrors.add(1);
    }
}

// teardown
export function teardown(data) {
    const prom   = scrapePrometheus(BASE_URL);
    const final_ = fetchFinalStock(BASE_URL, data.ticketTypeId);

    const report = {
        finalStock: final_ ? final_.stock : null,
        // custom Trend values aren't accessible in teardown; see write_latency in k6 summary
        writePath: {
            note: 'see write_latency in k6 summary above for p50/p95/max',
        },
        prometheus: prom,
        comparison: {
            baseline: 'redis-down-fallback (archived): 37,329 writes, 36,823 sold-out hitting DB',
        },
    };

    console.log(`\n[teardown] ${JSON.stringify(report, null, 2)}`);
}

// summary
export function handleSummary(data) {
    return {
        stdout: textSummary(data, { indent: '  ', enableColors: true }),
        'docs/benchmarks/results/flash-sale-realistic.json': JSON.stringify(data, null, 2),
    };
}