// docs/benchmarks/scripts/phase2-fallback.js
//
// Phase 2 degradation path: Redis completely down, system falls back to
// DB optimistic locking via RedisInventoryAdapter → InventoryAdapter.
//
// WHAT TO PROVE:
//   - error_rate_pct ≈ 0% — system stays functional, no 5xx storms
//   - QPS drops vs Phase 2 Redis (expected: Redis timeout adds latency)
//   - lock_conflict_count is non-zero (DB optimistic lock fallback is active)
//   - success_rate drops vs Phase 2 Redis (lock contention resumes)
//   - This verifies Redis is a soft dependency: degraded but not dead

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';
import { prepareTestData } from './setup.js';

const orderSuccessCount = new Counter('order_success_count');
const orderFailCount    = new Counter('order_fail_count');
const lockConflictCount = new Counter('lock_conflict_count');
const soldOutCount      = new Counter('sold_out_count');
const orderLatency      = new Trend('order_latency_ms', true);
const errorRate         = new Rate('error_rate');

export const options = {
    summaryTrendStats: ['p(50)', 'p(90)', 'p(95)', 'p(99)', 'max'],
    scenarios: {
        flash_sale: {
            executor: 'constant-vus',
            // 40 VUs with sleep(0.1): same parameters as Phase 1 baseline
            // so the comparison is apples-to-apples.
            // Redis is down — each request incurs a 2s connection timeout
            // before falling back to DB. QPS will be lower than Phase 1.
            vus: 40,
            duration: '60s',
        },
    },
    thresholds: {
        error_rate: ['rate<0.05'],
    },
};

export function setup() {
    return prepareTestData();
}

export default function (data) {
    const { ticketTypeId, userToken } = data;

    const payload = JSON.stringify({
        ticketTypeId: ticketTypeId,
        quantity: 1,
        requestId: uuidv4(),
    });

    const headers = {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${userToken}`,
    };

    const start = Date.now();
    const res = http.post(`${__ENV.BASE_URL || 'http://localhost:8080'}/api/v1/orders`,
        payload, { headers });
    const elapsed = Date.now() - start;

    orderLatency.add(elapsed);
    errorRate.add(res.status >= 500);

    if (res.status === 200 || res.status === 201) {
        orderSuccessCount.add(1);
    } else if (res.status === 400) {
        let code = 0;
        try { code = JSON.parse(res.body).code; } catch (_) {}
        if (code === 30005)      lockConflictCount.add(1);
        else if (code === 30004) soldOutCount.add(1);
        else                     orderFailCount.add(1);
    } else {
        orderFailCount.add(1);
    }

    sleep(0.1);
}

export function handleSummary(data) {
    const m = data.metrics;
    const successCount = m.order_success_count?.values?.count ?? 0;
    const totalReqs    = m.http_reqs?.values?.count ?? 1;
    const successRate  = ((successCount / totalReqs) * 100).toFixed(2);

    const summary = {
        timestamp:      new Date().toISOString(),
        phase:          'Phase 2 — Redis DOWN (Graceful Degradation to DB)',
        vus:            40,
        duration:       '60s',
        total_requests: totalReqs,
        qps:            m.http_reqs?.values?.rate?.toFixed(1)              ?? 'n/a',
        p50_ms:         m.order_latency_ms?.values?.['p(50)']?.toFixed(0)  ?? 'n/a',
        p95_ms:         m.order_latency_ms?.values?.['p(95)']?.toFixed(0)  ?? 'n/a',
        p99_ms:         m.order_latency_ms?.values?.['p(99)']?.toFixed(0)  ?? 'n/a',
        order_success:  successCount,
        success_rate:   successRate + '%',
        lock_conflicts: m.lock_conflict_count?.values?.count               ?? 0,
        sold_out:       m.sold_out_count?.values?.count                    ?? 0,
        hard_errors:    m.order_fail_count?.values?.count                  ?? 0,
        error_rate_pct: ((m.error_rate?.values?.rate ?? 0) * 100).toFixed(2) + '%',
    };

    console.log('\n========== PHASE 2 FALLBACK (REDIS DOWN) RESULTS ==========');
    console.log(JSON.stringify(summary, null, 2));
    console.log('==============================================================\n');
    console.log('Key question: is error_rate_pct near 0%?');
    console.log('If yes: graceful degradation proven — system alive without Redis.');
    console.log(`  success_rate: ${successRate}% (expect lower than Phase 2 Redis)`);
    console.log(`  lock_conflicts: ${summary.lock_conflicts} (expect non-zero — DB fallback active)`);

    return {};
}