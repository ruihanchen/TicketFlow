package com.chendev.ticketflow.event.dto;

import com.chendev.ticketflow.event.entity.Event;
import com.chendev.ticketflow.event.entity.EventStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class EventResponse {

    private Long id;
    private String name;
    private String description;
    private String venue;
    private LocalDateTime eventDate;
    private LocalDateTime saleStartTime;
    private LocalDateTime saleEndTime;
    private EventStatus status;
    private boolean onSale;
    private List<TicketTypeResponse> ticketTypes;
    private LocalDateTime createdAt;

    public static EventResponse from(Event event, List<TicketTypeResponse> ticketTypes) {
        return EventResponse.builder()
                .id(event.getId())
                .name(event.getName())
                .description(event.getDescription())
                .venue(event.getVenue())
                .eventDate(event.getEventDate())
                .saleStartTime(event.getSaleStartTime())
                .saleEndTime(event.getSaleEndTime())
                .status(event.getStatus())
                .onSale(event.isOnSale())
                .ticketTypes(ticketTypes)
                .createdAt(event.getCreatedAt())
                .build();
    }
}
