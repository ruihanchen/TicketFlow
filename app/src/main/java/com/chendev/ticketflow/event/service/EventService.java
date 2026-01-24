package com.chendev.ticketflow.event.service;

import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import com.chendev.ticketflow.event.repository.EventRepository;
import com.chendev.ticketflow.event.repository.TicketTypeRepository;
import com.chendev.ticketflow.event.port.InventoryInitPort;
import com.chendev.ticketflow.event.entity.Event;
import com.chendev.ticketflow.event.dto.CreateEventRequest;
import com.chendev.ticketflow.event.dto.EventResponse;

import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import com.chendev.ticketflow.event.entity.EventStatus;
import com.chendev.ticketflow.event.entity.TicketType;
import com.chendev.ticketflow.event.dto.TicketTypeResponse;
import com.chendev.ticketflow.common.exception.DomainException;
import com.chendev.ticketflow.common.response.ResultCode;

import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;


@Service
@RequiredArgsConstructor
public class EventService {

    private final EventRepository eventRepository;
    private final TicketTypeRepository ticketTypeRepository;
    private final InventoryInitPort inventoryInitPort;

    @Transactional
    public EventResponse createEvent(CreateEventRequest req) {
        Event event = Event.create(
                req.getName(), req.getDescription(), req.getVenue(),
                req.getEventDate(), req.getSaleStartTime(), req.getSaleEndTime());
        eventRepository.save(event);

        List<TicketType> ticketTypes = req.getTicketTypes().stream()
                .map(tt -> {
                    TicketType ticketType = TicketType.create(
                            event, tt.getName(), tt.getPrice(), tt.getTotalStock());
                    ticketTypeRepository.save(ticketType);
                    // port call keeps event domain from depending on inventory's service package directly
                    inventoryInitPort.initStock(ticketType.getId(), tt.getTotalStock());
                    return ticketType;
                })
                .toList();

        return toResponse(event, ticketTypes);
    }

    @Transactional(readOnly = true)
    public EventResponse getEvent(Long eventId) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> DomainException.of(ResultCode.EVENT_NOT_FOUND));
        return toResponse(event, ticketTypeRepository.findByEventId(eventId));
    }

    @Transactional(readOnly = true)
    public Page<EventResponse> listPublished(Pageable pageable) {
        Page<Event> events = eventRepository.findByStatus(EventStatus.PUBLISHED, pageable);

        // 2 queries flat vs N+1; @EntityGraph is off the table — see EventRepository
        List<Long> eventIds = events.stream().map(Event::getId).toList();
        Map<Long, List<TicketType>> ticketTypesByEvent = ticketTypeRepository
                .findByEventIdIn(eventIds)
                .stream()
                .collect(Collectors.groupingBy(tt -> tt.getEvent().getId()));

        return events.map(event ->
                toResponse(event, ticketTypesByEvent.getOrDefault(event.getId(), List.of())));
    }

    @Transactional
    public EventResponse publish(Long eventId) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> DomainException.of(ResultCode.EVENT_NOT_FOUND));
        event.publish();
        return toResponse(event, ticketTypeRepository.findByEventId(eventId));
    }

    private EventResponse toResponse(Event event, List<TicketType> ticketTypes) {
        List<TicketTypeResponse> ttResponses = ticketTypes.stream()
                .map(TicketTypeResponse::new)
                .toList();
        return new EventResponse(event, ttResponses);
    }
}