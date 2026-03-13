package com.chendev.ticketflow.order.dto;

import com.chendev.ticketflow.order.entity.Order;
import com.chendev.ticketflow.order.statemachine.OrderStatus;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Builder
public class OrderResponse {

    private Long id;
    private String orderNo;
    private Long userId;
    private Long ticketTypeId;
    private Integer quantity;
    private BigDecimal unitPrice;
    private BigDecimal totalAmount;
    private OrderStatus status;
    private LocalDateTime expiredAt;
    private LocalDateTime createdAt;

    public static OrderResponse from(Order order) {
        return OrderResponse.builder()
                .id(order.getId())
                .orderNo(order.getOrderNo())
                .userId(order.getUserId())
                .ticketTypeId(order.getTicketTypeId())
                .quantity(order.getQuantity())
                .unitPrice(order.getUnitPrice())
                .totalAmount(order.getTotalAmount())
                .status(order.getStatus())
                .expiredAt(order.getExpiredAt())
                .createdAt(order.getCreatedAt())
                .build();
    }
}