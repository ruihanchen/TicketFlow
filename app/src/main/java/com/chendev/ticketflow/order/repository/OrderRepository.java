package com.chendev.ticketflow.order.repository;

import com.chendev.ticketflow.order.entity.Order;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

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

    // Claims expired CREATED orders with FOR UPDATE SKIP LOCKED.
    // PAYING is hardcoded out of the query,no caller can accidentally cancel mid-payment orders.
    // lock.timeout=-2 maps to SKIP LOCKED: concurrent reapers get disjoint rows, never block each other.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")})
    @Query("SELECT o FROM Order o " +
            "WHERE o.status = com.chendev.ticketflow.order.statemachine.OrderStatus.CREATED " +
            "AND o.expiredAt < :now " +
            "ORDER BY o.expiredAt ASC")
    List<Order> findExpiredCreatedForUpdate(@Param("now") Instant now, Pageable pageable);
}