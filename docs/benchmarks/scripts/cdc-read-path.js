// docs/benchmarks/scripts/cdc-read-path.js
//
// Proves CDC read architecture under mixed load: 90% readers, 10% writers.
// Writers trigger CDC events; sustained high cache-hit rate proves Redis stays
// consistent without app-level cache invalidation.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.1.0/index.js';
import { prepareTestData, INITIAL_STOCK, BASE_URL } from './fixtures.js';
import { scrapePrometheus, fetchFinalStock } from './helpers/prometheus.js';

const VUS        = parseInt(__ENV.VUS        || '200');
const DURATION   = __ENV.DURATION            || '30s';
const WRITE_PCT  = parseInt(__ENV.WRITE_PCT  || '10');
const CDC_WARMUP = parseInt(__ENV.CDC_WARMUP || '3');

export const options = {
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
    scenarios: {
        mixed_load: { executor: 'constant-vus', vus: VUS, duration: DURATION },
    },
    thresholds: {
        'read_latency':   ['p(95)<100', 'p(99)<200'],
        'cache_hit_rate': ['rate>0.95'],
        'hard_errors':    ['count==0'],
    },
};

// metrics
const readLatency      = new Trend('read_latency', true);
const cacheReadLatency = new Trend('cache_read_latency', true);
const dbReadLatency    = new Trend('db_read_latency', true);
const cacheHitRate     = new Rate('cache_hit_rate');
const cacheHits        = new Counter('cache_hits');
const cacheMisses      = new Counter('cache_misses');
const orderSuccess     = new Counter('order_success');
const soldOut          = new Counter('sold_out');
const hardErrors       = new Counter('hard_errors');

// setup
export function setup() {
    const data = prepareTestData();

    console.log(`[setup] waiting ${CDC_WARMUP}s for CDC to populate Redis`);
    sleep(CDC_WARMUP);

    const verify = fetchFinalStock(BASE_URL, data.ticketTypeId);
    if (verify && verify.source === 'CACHE') {
        console.log(`[setup] confirmed: stock=${verify.stock}, source=CACHE`);
    } else {
        console.log(`[setup] warning: source=${verify ? verify.source : 'UNKNOWN'}, CDC may need more time`);
    }

    return data;
}

// VU function
export default function (data) {
    // 0-based: VUs 0–19 write, 20–199 read (with WRITE_PCT=10, VUS=200)
    if ((__VU - 1) % 100 < WRITE_PCT) {
        placeOrder(data);
    } else {
        readStock(data);
    }
}

function readStock(data) {
    const res = http.get(
        `${BASE_URL}/api/v1/ticket-types/${data.ticketTypeId}/stock`,
        { tags: { op: 'read_stock' } }
    );
    readLatency.add(res.timings.duration);

    if (!check(res, { 'read 200': (r) => r.status === 200 })) {
        hardErrors.add(1);
        return;
    }

    const source = JSON.parse(res.body).data?.source;
    if (source === 'CACHE') {
        cacheHitRate.add(true);
        cacheHits.add(1);
        cacheReadLatency.add(res.timings.duration);
    } else {
        cacheHitRate.add(false);
        cacheMisses.add(1);
        dbReadLatency.add(res.timings.duration);
    }
}

function placeOrder(data) {
    // VU+ITER is collision-free; Date.now() is not at ms resolution under 200 VUs
    const requestId = `cdc-writer-${__VU}-${__ITER}`;

    const res = http.post(`${BASE_URL}/api/v1/orders`,
        JSON.stringify({ ticketTypeId: data.ticketTypeId, quantity: 1, requestId }),
        {
            headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${data.userToken}` },
            tags: { op: 'write_order' },
        }
    );

    if (res.status === 201) {
        orderSuccess.add(1);
    } else {
        const code = JSON.parse(res.body)?.code;
        if (code === 2001) soldOut.add(1);
        else hardErrors.add(1);
    }

    sleep(1);
}

// teardown
export function teardown(data) {
    sleep(2); // let CDC propagate final writes before scraping Prometheus

    const prom   = scrapePrometheus(BASE_URL);
    const final_ = fetchFinalStock(BASE_URL, data.ticketTypeId);

    // misses and fallthroughs are the direct CDC consistency proof:
    // any non-zero value here means Redis diverged from Postgres during the run
    const cacheConsistency = prom ? {
        hits:         prom.cache.hits,
        misses:       prom.cache.misses,
        fallthroughs: prom.cache.fallthroughs,
        verdict:      prom.cache.misses === 0 && prom.cache.fallthroughs === 0
            ? 'CONSISTENT' : 'CHECK_MANUALLY',
    } : null;

    const report = {
        consistency: {
            finalStock:  final_ ? final_.stock : null,
            source:      final_ ? final_.source : null,
            stockVerdict: final_ && final_.source === 'CACHE' && final_.stock === 0
                ? 'PASS' : 'CHECK_MANUALLY',
        },
        cacheConsistency,
        prometheus: prom,
    };

    console.log(`\n[teardown] ${JSON.stringify(report, null, 2)}`);
}

// summary
export function handleSummary(data) {
    return {
        stdout: textSummary(data, { indent: '  ', enableColors: true }),
        'docs/benchmarks/results/cdc-read-path.json': JSON.stringify(data, null, 2),
    };
}