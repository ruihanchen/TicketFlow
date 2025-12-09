package com.chendev.ticketflow.event.controller;

import com.chendev.ticketflow.common.response.Result;
import com.chendev.ticketflow.event.dto.CreateEventRequest;
import com.chendev.ticketflow.event.dto.EventResponse;
import com.chendev.ticketflow.event.service.EventService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Result<EventResponse> create(@Valid @RequestBody CreateEventRequest req) {
        return Result.ok(eventService.createEvent(req));
    }

    @GetMapping("/{eventId}")
    public Result<EventResponse> get(@PathVariable Long eventId) {
        return Result.ok(eventService.getEvent(eventId));
    }

    //only returns PUBLISHED events;see EventService.listPublished()
    @GetMapping
    public Result<Page<EventResponse>> list(Pageable pageable) {
        return Result.ok(eventService.listPublished(pageable));
    }

    @PostMapping("/{eventId}/publish")
    public Result<EventResponse> publish(@PathVariable Long eventId) {
        return Result.ok(eventService.publish(eventId));
    }
}
