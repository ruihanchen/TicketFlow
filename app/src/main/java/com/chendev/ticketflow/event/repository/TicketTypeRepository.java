package com.chendev.ticketflow.event.repository;

import com.chendev.ticketflow.event.entity.TicketType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TicketTypeRepository extends JpaRepository<TicketType, Long> {

    // Fetches all ticket types for a given event
    List<TicketType> findByEventId(Long eventId);
}