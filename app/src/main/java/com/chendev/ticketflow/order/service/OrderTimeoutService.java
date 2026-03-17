package com.chendev.ticketflow.order.service;

import com.chendev.ticketflow.common.exception.BizException;
import com.chendev.ticketflow.order.entity.Order;
import com.chendev.ticketflow.order.repository.OrderRepository;
import com.chendev.ticketflow.order.statemachine.OrderStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderTimeoutService {

    private final OrderRepository orderRepository;
    private final OrderService orderService;

    @Scheduled(fixedDelayString = "${ticketflow.timeout.fixed-delay:60000}")
    public void cancelExpiredOrders() {
        List<Order> expiredOrders = orderRepository.findExpiredOrders(
                List.of(OrderStatus.CREATED, OrderStatus.PAYING),
                LocalDateTime.now()
        );

        if (expiredOrders.isEmpty()) return;

        log.info("[Timeout] Found {} expired orders to cancel", expiredOrders.size());

        for (Order order : expiredOrders) {
            try {
                orderService.cancelOrderBySystem(order.getOrderNo());
                log.info("[Timeout] Cancelled: orderNo={}", order.getOrderNo());
            } catch (BizException e) {
                // State machine rejected — order already in terminal state.
                // This is idempotency working correctly, not an error.
                log.warn("[Timeout] Order already in terminal state: orderNo={}, status={}",
                        order.getOrderNo(), order.getStatus());
            } catch (Exception e) {
                log.error("[Timeout] Failed to cancel: orderNo={}, error={}",
                        order.getOrderNo(), e.getMessage(), e);
            }
        }
    }
}