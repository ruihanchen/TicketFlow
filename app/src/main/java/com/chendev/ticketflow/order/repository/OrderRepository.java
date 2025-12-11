package com.chendev.ticketflow.order.repository;

import com.chendev.ticketflow.order.entity.Order;
import com.chendev.ticketflow.order.statemachine.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByOrderNo(String orderNo);

    // single query fuses existence + ownership. 404 either way(don't leak).
    Optional<Order> findByOrderNoAndUserId(String orderNo, Long userId);

    Optional<Order> findByRequestId(String requestId);

    boolean existsByRequestId(String requestId);

    Page<Order> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    // ORDER BY expired_at ASC keeps batch boundaries stable across consecutive runs
    @Query("SELECT o FROM Order o WHERE o.status IN :statuses " +
            "AND o.expiredAt < :now ORDER BY o.expiredAt ASC")
    List<Order> findExpiredOrders(List<OrderStatus> statuses,
                                  Instant now,
                                  Pageable pageable);
}