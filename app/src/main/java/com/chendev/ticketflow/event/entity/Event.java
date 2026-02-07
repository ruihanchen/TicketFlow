package com.chendev.ticketflow.event.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Entity
@Table(name = "events")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Event {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 200)
    private String venue;

    @Column(nullable = false, name = "event_date")
    private LocalDateTime eventDate;

    @Column(nullable = false, name = "sale_start_time")
    private LocalDateTime saleStartTime;

    @Column(nullable = false, name = "sale_end_time")
    private LocalDateTime saleEndTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EventStatus status;

    // One event has many ticket types
    // CascadeType.ALL: operations on Event cascade to TicketTypes
    // orphanRemoval: removing a TicketType from this list deletes it from DB
    @OneToMany(mappedBy = "event", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<TicketType> ticketTypes = new ArrayList<>();

    @CreatedDate
    @Column(nullable = false, updatable = false, name = "created_at")
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false, name = "updated_at")
    private LocalDateTime updatedAt;

    public static Event create(String name, String description, String venue,
                               LocalDateTime eventDate,
                               LocalDateTime saleStartTime,
                               LocalDateTime saleEndTime) {
        Event event = new Event();
        event.name = name;
        event.description = description;
        event.venue = venue;
        event.eventDate = eventDate;
        event.saleStartTime = saleStartTime;
        event.saleEndTime = saleEndTime;
        event.status = EventStatus.DRAFT;
        return event;
    }

    // Domain behavior — publish an event
    public void publish() {
        if (this.status != EventStatus.DRAFT) {
            throw new IllegalStateException(
                    "Only DRAFT events can be published, current status: " + this.status);
        }
        this.status = EventStatus.PUBLISHED;
    }

    public void cancel() {
        if (this.status == EventStatus.CANCELLED) {
            throw new IllegalStateException("Event is already cancelled");
        }
        this.status = EventStatus.CANCELLED;
    }

    // Return immutable view — callers cannot modify the internal list directly
    public List<TicketType> getTicketTypes() {
        return Collections.unmodifiableList(ticketTypes);
    }

    // Check if event is currently on sale
    public boolean isOnSale() {
        LocalDateTime now = LocalDateTime.now();
        return status == EventStatus.PUBLISHED
                && now.isAfter(saleStartTime)
                && now.isBefore(saleEndTime);
    }
}
