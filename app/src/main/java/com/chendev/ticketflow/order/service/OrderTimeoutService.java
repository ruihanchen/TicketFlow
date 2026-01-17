package com.chendev.ticketflow.order.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderTimeoutService {

    @Value("${ticketflow.timeout.max-per-cycle:5000}")
    private int maxPerCycle; // caps scheduler thread time if backlog spikes; remainder deferred to next tick

    private final OrderService orderService;

    // fixedDelay = idle interval between cycles, not a throughput cap.
    // Per-order TX with FOR UPDATE SKIP LOCKED; multiple reapers partition work without external coordination.
    @Scheduled(fixedDelayString = "${ticketflow.timeout.fixed-delay:60000}")
    public void cancelExpiredOrders() {
        int totalCancelled = 0;
        int failures = 0;
        for (int i = 0; i < maxPerCycle; i++) {
            try {
                boolean processed = orderService.processOneExpiredOrder();
                if (!processed) {
                    break;  // no expired CREATED orders left
                }
                totalCancelled++;
            } catch (Exception e) {
                // per-order isolation: one failure doesn't stop the loop.
                // with SKIP LOCKED + per-order TX, an exception here means a real problem, not a lock race.
                failures++;
                log.error("[Timeout] failed to process expired order: {}", e.getMessage());
            }
        }

        if (totalCancelled > 0 || failures > 0) {
            log.info("[Timeout] processed {} expired orders ({} failures)",
                    totalCancelled, failures);
        }

        if (totalCancelled == maxPerCycle) {
            log.warn("[Timeout] hit max-per-cycle limit ({}); remaining backlog deferred to next tick",
                    maxPerCycle);
        }
    }
}