package com.chendev.ticketflow.event.repository;

import com.chendev.ticketflow.event.entity.TicketType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TicketTypeRepository extends JpaRepository<TicketType, Long> {

    // JOIN FETCHes Event so EventAdapter can call tt.getEvent().isOnSale() without an open session.
    // Plain findById leaves a LAZY proxy that throws outside the caller's @Transactional boundary.
    @EntityGraph(attributePaths = {"event"})
    @Query("SELECT tt FROM TicketType tt WHERE tt.id = :id")
    Optional<TicketType> findByIdWithEvent(@Param("id") Long id);

    List<TicketType> findByEventId(Long eventId);

    //batch-loads ticket types for a page of events; avoids N+1 without @EntityGraph (see EventRepository)
    List<TicketType> findByEventIdIn(List<Long> eventIds);
}