package com.chendev.ticketflow.order.repository;

import com.chendev.ticketflow.order.entity.Order;
import com.chendev.ticketflow.order.statemachine.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByOrderNo(String orderNo);

    Optional<Order> findByRequestId(String requestId);

    // Check idempotency key before creating order
    boolean existsByRequestId(String requestId);

    // User's order history — paginated
    Page<Order> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    // Finds expired unpaid orders for the timeout cancellation job
    // Only scans CREATED and PAYING orders — terminal states are excluded
    @Query("""
            SELECT o FROM Order o
            WHERE o.status IN :statuses
            AND o.expiredAt < :now
            """)
    List<Order> findExpiredOrders(List<OrderStatus> statuses, LocalDateTime now);
}
