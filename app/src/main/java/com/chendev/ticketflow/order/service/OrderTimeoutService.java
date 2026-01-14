package com.chendev.ticketflow.order.service;

import com.chendev.ticketflow.order.entity.Order;
import com.chendev.ticketflow.order.repository.OrderRepository;
import com.chendev.ticketflow.order.statemachine.OrderStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderTimeoutService {

    @Value("${ticketflow.timeout.batch-size:100}")
    private int batchSize;

    // MAX_ROUNDS caps runaway loops on unexpectedly large backlogs; remainder caught next cycle.
    @Value("${ticketflow.timeout.max-rounds:50}")
    private int maxRounds;

    private final OrderRepository orderRepository;
    private final OrderService    orderService;

    // fixedDelay = idle interval, not a throughput cap.
    // Drains the full backlog in one run; overflow deferred to next cycle.
    @Scheduled(fixedDelayString = "${ticketflow.timeout.fixed-delay:60000}")
    public void cancelExpiredOrders() {
        int totalCancelled = 0;
        int skippedDueToRace = 0;
        int round = 0;
        List<Order> batch;

        do {
            batch = orderRepository.findExpiredOrders(
                    List.of(OrderStatus.CREATED, OrderStatus.PAYING),
                    Instant.now(),
                    PageRequest.of(0, batchSize)
            );

            for (Order order : batch) {
                try {
                    orderService.cancelOrderBySystem(order.getOrderNo());
                    totalCancelled++;
                } catch (ObjectOptimisticLockingFailureException e) {
                    //User won the race, they transitioned this order before our save landed. Back off and let it ride
                    //it'll come back around if it's still expired(INFO not ERROR)this is the lock working as designed.
                    skippedDueToRace++;
                    log.info("[Timeout] orderNo={} modified concurrently, skipping (will re-check next cycle)",
                            order.getOrderNo());
                } catch (Exception e) {
                    // per-order isolation:one failure doesn't block the batch
                    log.error("[Timeout] failed to cancel orderNo={}, error={}",
                            order.getOrderNo(), e.getMessage());
                }
            }

            round++;
        } while (batch.size() == batchSize && round < maxRounds);

        if (round == maxRounds && batch.size() == batchSize) {
            log.warn("[Timeout] hit max-rounds limit ({}), some expired orders " +
                    "will be processed next cycle", maxRounds);
        }

        if (totalCancelled > 0 || skippedDueToRace > 0) {
            log.info("[Timeout] cancelled {} expired orders in {} rounds (skipped {} due to concurrent user actions)",
                    totalCancelled, round, skippedDueToRace);
        }
    }
}