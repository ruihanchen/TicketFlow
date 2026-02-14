package com.chendev.ticketflow.event.controller;

import com.chendev.ticketflow.common.response.PageResult;
import com.chendev.ticketflow.common.response.Result;
import com.chendev.ticketflow.event.dto.CreateEventRequest;
import com.chendev.ticketflow.event.dto.EventResponse;
import com.chendev.ticketflow.event.service.EventService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    // Admin only — create event
    @PostMapping("/admin/events")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public Result<EventResponse> createEvent(@Valid @RequestBody CreateEventRequest request) {
        return Result.success(eventService.createEvent(request));
    }

    // Admin only — publish event
    @PutMapping("/admin/events/{eventId}/publish")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<EventResponse> publishEvent(@PathVariable Long eventId) {
        return Result.success(eventService.publishEvent(eventId));
    }

    // Admin only — cancel event
    @PutMapping("/admin/events/{eventId}/cancel")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<EventResponse> cancelEvent(@PathVariable Long eventId) {
        return Result.success(eventService.cancelEvent(eventId));
    }

    // Public — get single event
    @GetMapping("/events/{eventId}")
    public Result<EventResponse> getEvent(@PathVariable Long eventId) {
        return Result.success(eventService.getEvent(eventId));
    }

    // Public — list published events with pagination
    @GetMapping("/events")
    public Result<PageResult<EventResponse>> getPublishedEvents(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        PageRequest pageable = PageRequest.of(page, size,
                Sort.by(Sort.Direction.ASC, "eventDate"));
        return Result.success(eventService.getPublishedEvents(pageable));
    }

    // Public — list currently on-sale events
    @GetMapping("/events/on-sale")
    public Result<PageResult<EventResponse>> getOnSaleEvents(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        PageRequest pageable = PageRequest.of(page, size,
                Sort.by(Sort.Direction.ASC, "eventDate"));
        return Result.success(eventService.getOnSaleEvents(pageable));
    }
}