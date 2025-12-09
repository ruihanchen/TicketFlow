package com.chendev.ticketflow.event.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.chendev.ticketflow.event.entity.Event;
import com.chendev.ticketflow.event.entity.EventStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface EventRepository extends JpaRepository<Event, Long> {

    // avoid @EntityGraph here to prevents Hibernate in-memory pagination on OneToMany joins.
    // TicketTypes are batch loaded separately via findByEventIdIn()
    Page<Event> findByStatus(EventStatus status, Pageable pageable);
}
