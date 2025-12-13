package com.chendev.ticketflow.event.controller;


import org.springframework.web.bind.annotation.*;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import com.chendev.ticketflow.event.dto.EventResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import com.chendev.ticketflow.event.dto.CreateEventRequest;

import com.chendev.ticketflow.event.service.EventService;

import org.springframework.http.HttpStatus;
import com.chendev.ticketflow.common.response.Result;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;



@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
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
    @PreAuthorize("hasRole('ADMIN')")
    public Result<EventResponse> publish(@PathVariable Long eventId) {
        return Result.ok(eventService.publish(eventId));
    }
}
