package com.chendev.ticketflow.order.entity;

import com.chendev.ticketflow.order.statemachine.OrderEvent;
import com.chendev.ticketflow.order.statemachine.OrderStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "order_status_history")
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class OrderStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private OrderStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    private OrderEvent event;

    @Column(length = 200)
    private String reason;

    private Instant createdAt;

    //package-private: history is written only by Order.transitionTo(), not by external callers
    static OrderStatusHistory record(Order order, OrderStatus from,
                                     OrderStatus to, OrderEvent event, String reason) {
        OrderStatusHistory h = new OrderStatusHistory();
        h.order = order;
        h.fromStatus = from;
        h.toStatus = to;
        h.event = event;
        h.reason = reason;
        h.createdAt = Instant.now();
        return h;
    }
}