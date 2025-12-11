package com.chendev.ticketflow.order.entity;

import com.chendev.ticketflow.common.exception.DomainException;
import com.chendev.ticketflow.common.response.ResultCode;
import com.chendev.ticketflow.order.statemachine.OrderEvent;
import com.chendev.ticketflow.order.statemachine.OrderStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "orders")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class Order {

    //EnumMap + EnumSet: O(1) lookup, no hashing overhead, memory-efficient for small enum sets
    private static final Map<OrderStatus, Set<OrderStatus>> TRANSITIONS;

    static {
        TRANSITIONS = new EnumMap<>(OrderStatus.class);
        TRANSITIONS.put(OrderStatus.CREATED,
                EnumSet.of(OrderStatus.PAYING, OrderStatus.CANCELLED));
        TRANSITIONS.put(OrderStatus.PAYING,
                EnumSet.of(OrderStatus.PAID, OrderStatus.CANCELLED));
        TRANSITIONS.put(OrderStatus.PAID,
                EnumSet.of(OrderStatus.CONFIRMED));
        //terminal states(no transitions out)
        TRANSITIONS.put(OrderStatus.CONFIRMED, EnumSet.noneOf(OrderStatus.class));
        TRANSITIONS.put(OrderStatus.CANCELLED, EnumSet.noneOf(OrderStatus.class));
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 64)
    private String orderNo;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long ticketTypeId;

    @Column(nullable = false)
    private Integer quantity;

    //prices drift; snapshot at order time so receipts stay accurate
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @Column(unique = true, nullable = false, length = 64)
    private String requestId;

    @Column(nullable = false)
    private Instant expiredAt;

    @CreatedDate
    @Column(nullable = false, updatable = false, name = "created_at")
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false, name = "updated_at")
    private Instant updatedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<OrderStatusHistory> statusHistory = new ArrayList<>();

    public static Order create(String orderNo, Long userId, Long ticketTypeId,
                               int quantity, BigDecimal unitPrice, String requestId,
                               Duration paymentWindow) {
        Order order = new Order();
        order.orderNo = orderNo;
        order.userId = userId;
        order.ticketTypeId = ticketTypeId;
        order.quantity = quantity;
        order.unitPrice = unitPrice;
        order.totalAmount = unitPrice.multiply(BigDecimal.valueOf(quantity));
        order.status = OrderStatus.CREATED;
        order.requestId = requestId;
        order.expiredAt = Instant.now().plus(paymentWindow);
        return order;
    }

    public void transitionTo(OrderStatus newStatus, OrderEvent event, String reason) {
        Set<OrderStatus> allowed = TRANSITIONS.getOrDefault(this.status, EnumSet.noneOf(OrderStatus.class));
        if (!allowed.contains(newStatus)) {
            throw DomainException.of(ResultCode.INVALID_STATE_TRANSITION,
                    String.format("cannot go from %s to %s", this.status, newStatus));
        }
        //must record before updating status -- record() reads this.status as fromStatus
        statusHistory.add(OrderStatusHistory.record(this, this.status, newStatus, event, reason));
        this.status = newStatus;
    }
    //null guard is defensive:protects against Order instances created outside of create() in tests
    public boolean isExpired() {
        return expiredAt != null && Instant.now().isAfter(expiredAt);
    }
}