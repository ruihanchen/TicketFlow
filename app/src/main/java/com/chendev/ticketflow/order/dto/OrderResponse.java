package com.chendev.ticketflow.order.dto;

import com.chendev.ticketflow.order.entity.Order;
import com.chendev.ticketflow.order.statemachine.OrderStatus;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
public class OrderResponse {

    private final Long id;
    private final String orderNo;
    private final Long ticketTypeId;
    private final Integer quantity;
    private final BigDecimal unitPrice;
    private final BigDecimal totalAmount;
    private final OrderStatus status;
    //cart hold deadline, user can no longer initiate payment after this
    private final Instant expiredAt;
    //payment completion deadline, null until user clicks pay; frontend shows this timer in PAYING state
    private final Instant paymentExpiredAt;
    private final Instant createdAt;

    public OrderResponse(Order order) {
        this.id = order.getId();
        this.orderNo = order.getOrderNo();
        this.ticketTypeId = order.getTicketTypeId();
        this.quantity = order.getQuantity();
        this.unitPrice = order.getUnitPrice();
        this.totalAmount = order.getTotalAmount();
        this.status = order.getStatus();
        this.expiredAt = order.getExpiredAt();
        this.paymentExpiredAt = order.getPaymentExpiredAt();
        this.createdAt = order.getCreatedAt();
    }
}