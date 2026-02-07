package com.chendev.ticketflow.event.repository;

import com.chendev.ticketflow.event.entity.Event;
import com.chendev.ticketflow.event.entity.EventStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;

public interface EventRepository extends JpaRepository<Event, Long>{
    // Returns paginated published events — used for public event listing
    Page<Event> findByStatus(EventStatus status, Pageable pageable);

    // Finds events currently on sale — used for sale validation
    // JPQL query: operates on entity fields, not table columns
    @Query("""
            SELECT e FROM Event e
            WHERE e.status = 'PUBLISHED'
            AND e.saleStartTime <= :now
            AND e.saleEndTime >= :now
            """)
    Page<Event> findCurrentlyOnSaleEvents(LocalDateTime now, Pageable pageable);
}
