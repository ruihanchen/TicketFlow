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

    boolean existsByRequestId(String requestId);

    Page<Order> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    // Paginated overload — used by OrderTimeoutService in production.
    // Limits each polling cycle to a fixed batch size (e.g. 100) to prevent OOM
    // when a flash sale produces thousands of simultaneous expired orders.
    // Prefer this overload in all scheduled jobs.
    @Query("""
            SELECT o FROM Order o
            WHERE o.status IN :statuses
            AND o.expiredAt < :now
            """)
    List<Order> findExpiredOrders(List<OrderStatus> statuses, LocalDateTime now,
                                  Pageable pageable);

    // Unbounded overload — retained for tests that need deterministic full-scan.
    // Do NOT use in production scheduled jobs: loading an unbounded result set
    // into a List triggers OOM when expired order counts are large.
    @Query("""
            SELECT o FROM Order o
            WHERE o.status IN :statuses
            AND o.expiredAt < :now
            """)
    List<Order> findExpiredOrders(List<OrderStatus> statuses, LocalDateTime now);
}
