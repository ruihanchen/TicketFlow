package com.chendev.ticketflow.order.service;

import com.chendev.ticketflow.common.exception.BizException;
import com.chendev.ticketflow.order.entity.Order;
import com.chendev.ticketflow.order.repository.OrderRepository;
import com.chendev.ticketflow.order.statemachine.OrderStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderTimeoutService {

    private final OrderRepository orderRepository;
    private final OrderService    orderService;

    // Maximum orders processed per polling cycle.
    // Prevents OOM in flash-sale scenarios where thousands of orders expire
    // simultaneously — loading them all into a List would exhaust JVM heap.
    // 100 orders/cycle × 60s interval = 6,000 orders/min cancellation throughput,
    // which exceeds realistic peak cancellation rates for a single-node service.
    private static final int BATCH_SIZE = 100;

    @Scheduled(fixedDelayString = "${ticketflow.timeout.fixed-delay:60000}")
    public void cancelExpiredOrders() {
        List<Order> expiredOrders = orderRepository.findExpiredOrders(
                List.of(OrderStatus.CREATED, OrderStatus.PAYING),
                LocalDateTime.now(),
                PageRequest.of(0, BATCH_SIZE)
        );

        if (expiredOrders.isEmpty()) return;

        log.info("[Timeout] Found {} expired orders to cancel (batch limit={})",
                expiredOrders.size(), BATCH_SIZE);

        for (Order order : expiredOrders) {
            try {
                // Pass orderNo rather than the Order entity.
                // The entities returned by findExpiredOrders() are detached after
                // the query's implicit transaction closes. Passing a detached entity
                // to cancelOrderBySystem() would cause LazyInitializationException
                // when transitionTo() appends to the statusHistory collection.
                // cancelOrderBySystem(String) reloads a fresh attached entity internally.
                orderService.cancelOrderBySystem(order.getOrderNo());
                log.info("[Timeout] Cancelled: orderNo={}", order.getOrderNo());
            } catch (BizException e) {
                // State machine rejected the transition — order already in a
                // terminal state. This is idempotency working correctly.
                log.warn("[Timeout] Order already in terminal state: orderNo={}, status={}",
                        order.getOrderNo(), order.getStatus());
            } catch (Exception e) {
                // Per-order isolation: one failure does not abort the batch.
                log.error("[Timeout] Failed to cancel: orderNo={}, error={}",
                        order.getOrderNo(), e.getMessage(), e);
            }
        }
    }
}