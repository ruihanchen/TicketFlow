package com.chendev.ticketflow.event.dto;

import lombok.Getter;

import java.time.Instant;
import java.util.List;

import com.chendev.ticketflow.event.entity.Event;
import com.chendev.ticketflow.event.entity.EventStatus;

@Getter
public class EventResponse {

    private final Long id;
    private final String name;
    private final String description;
    private final String venue;
    private final Instant eventDate;
    private final Instant saleStartTime;
    private final Instant saleEndTime;
    private final EventStatus status;
    private final boolean onSale;
    private final List<TicketTypeResponse> ticketTypes;
    private final Instant createdAt;
    private final Instant updatedAt;

    public EventResponse(Event event, List<TicketTypeResponse> ticketTypes) {
        this.id = event.getId();
        this.name = event.getName();
        this.description = event.getDescription();
        this.venue = event.getVenue();
        this.eventDate = event.getEventDate();
        this.saleStartTime = event.getSaleStartTime();
        this.saleEndTime = event.getSaleEndTime();
        this.status = event.getStatus();
        this.onSale = event.isOnSale();
        this.ticketTypes = ticketTypes;
        this.createdAt = event.getCreatedAt();
        this.updatedAt = event.getUpdatedAt();
    }
}
