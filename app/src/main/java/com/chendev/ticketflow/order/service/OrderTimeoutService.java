package com.chendev.ticketflow.order.service;

import com.chendev.ticketflow.order.entity.Order;
import com.chendev.ticketflow.order.port.InventoryPort;
import com.chendev.ticketflow.order.repository.OrderRepository;
import com.chendev.ticketflow.order.statemachine.OrderEvent;
import com.chendev.ticketflow.order.statemachine.OrderStateMachine;
import com.chendev.ticketflow.order.statemachine.OrderStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderTimeoutService {

    private final OrderRepository orderRepository;
    private final InventoryPort inventoryPort;
    private final OrderStateMachine orderStateMachine;

    // Runs every 60 seconds
    // Phase 2: replace with Redis delayed queue for precision and scalability
    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void cancelExpiredOrders() {
        List<Order> expiredOrders = orderRepository.findExpiredOrders(
                List.of(OrderStatus.CREATED, OrderStatus.PAYING),
                LocalDateTime.now()
        );

        if (expiredOrders.isEmpty()) {
            return;
        }

        log.info("[Timeout] Found {} expired orders to cancel", expiredOrders.size());

        for (Order order : expiredOrders) {
            try {
                orderStateMachine.handleEvent(order, OrderEvent.SYSTEM_TIMEOUT,
                        "Order expired after 15 minutes");

                // Release inventory back to available pool
                inventoryPort.releaseStock(order.getTicketTypeId(), order.getQuantity());

                orderRepository.save(order);

                log.info("[Timeout] Cancelled expired order: orderNo={}", order.getOrderNo());

            } catch (Exception e) {
                // Log and continue — don't let one failure block the rest
                log.error("[Timeout] Failed to cancel order: orderNo={}, error={}",
                        order.getOrderNo(), e.getMessage(), e);
            }
        }
    }
}