package com.chendev.ticketflow.event.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import com.chendev.ticketflow.event.entity.TicketType;

public interface TicketTypeRepository extends JpaRepository<TicketType, Long> {

    List<TicketType> findByEventId(Long eventId);

    //batch-loads ticket types for a page of events; avoids N+1 without @EntityGraph (see EventRepository)
    List<TicketType> findByEventIdIn(List<Long> eventIds);
}