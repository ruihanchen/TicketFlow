package com.chendev.ticketflow.order.entity;

import com.chendev.ticketflow.order.statemachine.OrderEvent;
import com.chendev.ticketflow.order.statemachine.OrderStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "order_status_history")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderStatusHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    private OrderEvent event;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 20)
    private OrderStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, name = "to_status", length = 20)
    private OrderStatus toStatus;

    @Column(length = 200)
    private String reason;

    @CreatedDate
    @Column(nullable = false, updatable = false, name = "created_at")
    private LocalDateTime createdAt;

    // Package-private factory — only Order.transitionTo() should call this
    static OrderStatusHistory record(Order order, OrderStatus from,
                                     OrderStatus to, OrderEvent event, String reason) {
        OrderStatusHistory history = new OrderStatusHistory();
        history.order = order;
        history.fromStatus = from;
        history.toStatus = to;
        history.event = event;
        history.reason = reason;
        return history;
    }
}
