package com.chendev.ticketflow.order.entity;

import com.chendev.ticketflow.order.statemachine.OrderEvent;
import com.chendev.ticketflow.order.statemachine.OrderStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Entity
@Table(name = "orders")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

    // Order expiry duration: 15 minutes
    private static final int EXPIRY_MINUTES = 15;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Business identifier exposed to users and external systems
    // Never expose the auto-increment id externally — it leaks business volume
    @Column(nullable = false, unique = true, name = "order_no", length = 32)
    private String orderNo;

    @Column(nullable = false, name = "user_id")
    private Long userId;

    @Column(nullable = false, name = "ticket_type_id")
    private Long ticketTypeId;

    @Column(nullable = false)
    private Integer quantity;

    // Price snapshot at order creation time
    // Must NOT reference TicketType.price — ticket price can change after order
    @Column(nullable = false, name = "unit_price", precision = 10, scale = 2)
    private BigDecimal unitPrice;

    @Column(nullable = false, name = "total_amount", precision = 10, scale = 2)
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    // Idempotency key from frontend — DB UNIQUE constraint as last line of defense
    @Column(unique = true, name = "request_id", length = 64)
    private String requestId;

    @Column(name = "expired_at")
    private LocalDateTime expiredAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderStatusHistory> statusHistory = new ArrayList<>();

    @CreatedDate
    @Column(nullable = false, updatable = false, name = "created_at")
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false, name = "updated_at")
    private LocalDateTime updatedAt;

    public static Order create(String orderNo, Long userId, Long ticketTypeId,
                               Integer quantity, BigDecimal unitPrice, String requestId) {
        Order order = new Order();
        order.orderNo = orderNo;
        order.userId = userId;
        order.ticketTypeId = ticketTypeId;
        order.quantity = quantity;
        order.unitPrice = unitPrice;
        order.totalAmount = unitPrice.multiply(BigDecimal.valueOf(quantity));
        order.status = OrderStatus.CREATED;
        order.requestId = requestId;
        order.expiredAt = LocalDateTime.now().plusMinutes(EXPIRY_MINUTES);
        return order;
    }

    // Status transition — all transitions go through here
    // Actual validation is delegated to OrderStateMachine
    public void transitionTo(OrderStatus newStatus, OrderEvent event, String reason) {
        OrderStatusHistory history = OrderStatusHistory.record(
                this, this.status, newStatus, event, reason);
        this.statusHistory.add(history);
        this.status = newStatus;
    }

    public boolean isExpired() {
        return expiredAt != null && LocalDateTime.now().isAfter(expiredAt);
    }

    public boolean isCancellable() {
        return status == OrderStatus.CREATED || status == OrderStatus.PAYING;
    }

    public List<OrderStatusHistory> getStatusHistory() {
        return Collections.unmodifiableList(statusHistory);
    }
}
