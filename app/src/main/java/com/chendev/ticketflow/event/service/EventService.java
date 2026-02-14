package com.chendev.ticketflow.event.service;

import com.chendev.ticketflow.common.exception.BizException;
import com.chendev.ticketflow.common.exception.SystemException;
import com.chendev.ticketflow.common.response.PageResult;
import com.chendev.ticketflow.common.response.ResultCode;
import com.chendev.ticketflow.event.dto.CreateEventRequest;
import com.chendev.ticketflow.event.dto.EventResponse;
import com.chendev.ticketflow.event.dto.TicketTypeResponse;
import com.chendev.ticketflow.event.entity.Event;
import com.chendev.ticketflow.event.entity.EventStatus;
import com.chendev.ticketflow.event.entity.TicketType;
import com.chendev.ticketflow.event.repository.EventRepository;
import com.chendev.ticketflow.event.repository.TicketTypeRepository;
import com.chendev.ticketflow.inventory.entity.Inventory;
import com.chendev.ticketflow.inventory.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventService {

    private final EventRepository eventRepository;
    private final TicketTypeRepository ticketTypeRepository;
    private final InventoryRepository inventoryRepository;

    @Transactional
    public EventResponse createEvent(CreateEventRequest request) {
        // Validate sale time window
        if (!request.getSaleEndTime().isAfter(request.getSaleStartTime())) {
            throw new BizException(ResultCode.EVENT_NOT_AVAILABLE,
                    "Sale end time must be after sale start time");
        }

        // Create event
        Event event = Event.create(
                request.getName(),
                request.getDescription(),
                request.getVenue(),
                request.getEventDate(),
                request.getSaleStartTime(),
                request.getSaleEndTime()
        );
        eventRepository.save(event);

        // Create ticket types and initialize inventory for each
        // All in one transaction — event + ticket types + inventories together
        List<TicketTypeResponse> ticketTypeResponses = request.getTicketTypes().stream()
                .map(ttRequest -> createTicketTypeWithInventory(event, ttRequest))
                .toList();

        log.info("[Event] Created event: eventId={}, name={}, ticketTypes={}",
                event.getId(), event.getName(), ticketTypeResponses.size());

        return EventResponse.from(event, ticketTypeResponses);
    }

    private TicketTypeResponse createTicketTypeWithInventory(
            Event event, CreateEventRequest.TicketTypeRequest request) {

        TicketType ticketType = TicketType.create(
                event, request.getName(), request.getPrice(), request.getTotalStock());
        ticketTypeRepository.save(ticketType);

        Inventory inventory = Inventory.initialize(
                ticketType.getId(), request.getTotalStock());
        inventoryRepository.save(inventory);

        return TicketTypeResponse.from(ticketType, inventory.getAvailableStock());
    }

    @Transactional
    public EventResponse publishEvent(Long eventId) {
        Event event = findEventById(eventId);
        event.publish();  // Domain logic — validates status transition internally
        eventRepository.save(event);

        log.info("[Event] Published event: eventId={}", eventId);
        return buildEventResponse(event);
    }

    @Transactional
    public EventResponse cancelEvent(Long eventId) {
        Event event = findEventById(eventId);
        event.cancel();
        eventRepository.save(event);

        log.info("[Event] Cancelled event: eventId={}", eventId);
        return buildEventResponse(event);
    }

    @Transactional(readOnly = true)
    public EventResponse getEvent(Long eventId) {
        Event event = findEventById(eventId);
        return buildEventResponse(event);
    }

    @Transactional(readOnly = true)
    public PageResult<EventResponse> getPublishedEvents(Pageable pageable) {
        Page<EventResponse> page = eventRepository
                .findByStatus(EventStatus.PUBLISHED, pageable)
                .map(this::buildEventResponse);
        return PageResult.of(page);
    }

    @Transactional(readOnly = true)
    public PageResult<EventResponse> getOnSaleEvents(Pageable pageable) {
        Page<EventResponse> page = eventRepository
                .findCurrentlyOnSaleEvents(LocalDateTime.now(), pageable)
                .map(this::buildEventResponse);
        return PageResult.of(page);
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private Event findEventById(Long eventId) {
        return eventRepository.findById(eventId)
                .orElseThrow(() -> BizException.of(ResultCode.EVENT_NOT_FOUND,
                        "Event #" + eventId + " not found"));
    }

    private EventResponse buildEventResponse(Event event) {
        List<TicketTypeResponse> ticketTypeResponses = ticketTypeRepository
                .findByEventId(event.getId())
                .stream()
                .map(tt -> {
                    Integer available = inventoryRepository
                            .findByTicketTypeId(tt.getId())
                            .map(Inventory::getAvailableStock)
                            .orElseThrow(() -> new SystemException(ResultCode.INTERNAL_ERROR,
                                    "Inventory missing for ticketTypeId=" + tt.getId()));
                    return TicketTypeResponse.from(tt, available);
                })
                .toList();

        return EventResponse.from(event, ticketTypeResponses);
    }
}
