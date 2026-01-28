// docs/benchmarks/scripts/helpers/prometheus.js
//
// Shared Prometheus scraper for benchmark teardown. Eliminates regex duplication.

import http from 'k6/http';

// matches both bare metrics and labeled ones: name{label="val"} 42.0
function extractMetric(body, name) {
    const re = new RegExp(`^${name}(?:\\{[^}]*\\})?\\s+([\\d.]+)`, 'm');
    const match = body.match(re);
    return match ? parseFloat(match[1]) : null;
}

export function scrapePrometheus(baseUrl) {
    const res = http.get(`${baseUrl}/actuator/prometheus`);
    if (res.status !== 200) return null;

    const body = res.body;

    // CDC pipeline
    const lagSum   = extractMetric(body, 'ticketflow_cdc_lag_seconds_sum');
    const lagCount = extractMetric(body, 'ticketflow_cdc_lag_seconds_count');
    const cdcErrors = extractMetric(body, 'ticketflow_cdc_handler_errors_total');

    // Inventory cache
    const cacheHits   = extractMetric(body, 'ticketflow_inventory_query_cache_hits_total');
    const cacheMisses = extractMetric(body, 'ticketflow_inventory_query_cache_misses_total');
    const cacheFalls  = extractMetric(body, 'ticketflow_inventory_query_cache_fallthroughs_total');
    const cacheTotal  = (cacheHits || 0) + (cacheMisses || 0) + (cacheFalls || 0);

    // Write path
    const insufficientStock = extractMetric(body, 'ticketflow_inventory_insufficient_stock_total');

    // Order metrics
    const ordersCreated   = extractMetric(body, 'ticketflow_orders_success_total');
    const ordersDuplicate = extractMetric(body, 'ticketflow_orders_duplicate_total');

    // Reaper
    const reaperCancelled = extractMetric(body, 'ticketflow_order_reaper_cancelled_total');
    const reaperFailures  = extractMetric(body, 'ticketflow_order_reaper_failures_total');

    return {
        cdc: {
            avgLag:  lagCount > 0 ? +(lagSum / lagCount).toFixed(4) : null,
            errors:  cdcErrors || 0,
        },
        cache: {
            hits:         cacheHits || 0,
            misses:       cacheMisses || 0,
            fallthroughs: cacheFalls || 0,
            hitRate:      cacheTotal > 0 ? +((cacheHits || 0) / cacheTotal * 100).toFixed(1) : null,
        },
        write: {
            insufficientStock: insufficientStock || 0,
        },
        orders: {
            created:   ordersCreated || 0,
            duplicate: ordersDuplicate || 0,
        },
        reaper: {
            cancelled: reaperCancelled || 0,
            failures:  reaperFailures || 0,
        },
    };
}

export function fetchFinalStock(baseUrl, ticketTypeId) {
    const res = http.get(`${baseUrl}/api/v1/ticket-types/${ticketTypeId}/stock`);
    if (res.status !== 200) return null;
    const body = JSON.parse(res.body);
    return {
        stock:  body.data ? body.data.availableStock : null,
        source: body.data ? body.data.source : null,
    };
}