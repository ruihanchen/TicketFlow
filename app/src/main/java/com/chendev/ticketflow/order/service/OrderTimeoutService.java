package com.chendev.ticketflow.order.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class OrderTimeoutService {

    @Value("${ticketflow.timeout.max-per-cycle:5000}")
    private int maxPerCycle; // caps scheduler thread time if backlog spikes; remainder deferred to next tick

    private final OrderService orderService;

    // rate(cancelled[5m]) = reaper throughput; useful for capacity planning
    private final Counter cancelledCounter;

    // non-zero means a real fault, with SKIP LOCKED + per-order TX, lock contention never throws
    private final Counter failureCounter;

    // fires when the loop hits maxPerCycle without exhausting the backlog, backlog growing faster than drain rate
    private final Counter saturationCounter;

    // explicit constructor: final Counter fields must be registered here, before @Value injection runs
    public OrderTimeoutService(OrderService orderService, MeterRegistry meterRegistry) {
        this.orderService = orderService;

        this.cancelledCounter = Counter.builder("ticketflow_order_reaper_cancelled_total")
                .description("expired CREATED orders successfully cancelled by the reaper")
                .register(meterRegistry);

        this.failureCounter = Counter.builder("ticketflow_order_reaper_failures_total")
                .description("per-order exceptions in the reaper loop; non-zero = data inconsistency or DB fault")
                .register(meterRegistry);

        this.saturationCounter = Counter.builder("ticketflow_order_reaper_cycles_saturated_total")
                .description("reaper cycles that hit max-per-cycle without exhausting the backlog")
                .register(meterRegistry);
    }

    // fixedDelay = idle interval between cycles, not a throughput cap.
    // Per-order TX with FOR UPDATE SKIP LOCKED; multiple reapers partition work without external coordination.
    @Scheduled(fixedDelayString = "${ticketflow.timeout.fixed-delay:60000}")
    public void cancelExpiredOrders() {
        int totalCancelled = 0;
        int failures = 0;
        boolean backlogExhausted = false;

        for (int i = 0; i < maxPerCycle; i++) {
            try {
                boolean processed = orderService.processOneExpiredOrder();
                if (!processed) {
                    backlogExhausted = true;
                    break;  // no expired CREATED orders left
                }
                totalCancelled++;
                cancelledCounter.increment();
            } catch (Exception e) {
                // per-order isolation: one failure doesn't stop the loop.
                // with SKIP LOCKED + per-order TX, an exception here means a real problem, not a lock race.
                failures++;
                failureCounter.increment();
                log.error("[Timeout] failed to process expired order: {}", e.getMessage());
            }
        }

        if (totalCancelled > 0 || failures > 0) {
            log.info("[Timeout] processed {} expired orders ({} failures)",
                    totalCancelled, failures);
        }

        // guard against false positive when zero work done
        if (!backlogExhausted && (totalCancelled + failures) > 0) {
            saturationCounter.increment();
            log.warn("[Timeout] hit max-per-cycle limit ({}); remaining backlog deferred to next tick",
                    maxPerCycle);
        }
    }
}