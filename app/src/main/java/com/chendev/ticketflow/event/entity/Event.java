package com.chendev.ticketflow.event.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import com.chendev.ticketflow.common.exception.DomainException;
import com.chendev.ticketflow.common.response.ResultCode;


@Entity
@Table(name = "events")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
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

    @Column(nullable = false)
    private Instant eventDate;

    @Column(nullable = false)
    private Instant saleStartTime;

    @Column(nullable = false)
    private Instant saleEndTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EventStatus status = EventStatus.DRAFT;

    @OneToMany(mappedBy = "event", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<TicketType> ticketTypes = new ArrayList<>();

    @CreatedDate
    @Column(nullable = false, updatable = false, name = "created_at")
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false, name = "updated_at")
    private Instant updatedAt;

    public static Event create(String name, String description, String venue,
                               Instant eventDate,
                               Instant saleStartTime,
                               Instant saleEndTime) {
        validateTimeWindow(saleStartTime, saleEndTime, eventDate);

        Event e = new Event();
        e.name = name;
        e.description = description;
        e.venue = venue;
        e.eventDate = eventDate;
        e.saleStartTime = saleStartTime;
        e.saleEndTime = saleEndTime;
        e.status = EventStatus.DRAFT;
        return e;
    }
    // CANCELLED → PUBLISHED would reopen a closed ticket window(domain logic violation).
    // Order.transitionTo() has an explicit state machine; publish() needs the same defensive depth.
    public void publish() {
        if (this.status != EventStatus.DRAFT) {
            throw DomainException.of(ResultCode.BAD_REQUEST,
                    "only DRAFT events can be published, current status: " + this.status);
        }
        this.status = EventStatus.PUBLISHED;
    }

    public boolean isOnSale() {
        Instant now = Instant.now();
        return status == EventStatus.PUBLISHED
                && now.isAfter(saleStartTime)
                && now.isBefore(saleEndTime);
    }

    //frontend input is untrusted; invariants: saleEnd > saleStart, eventDate > saleEnd
    private static void validateTimeWindow(Instant saleStart,
                                           Instant saleEnd,
                                           Instant eventDate) {
        if (!saleEnd.isAfter(saleStart)) {
            throw DomainException.of(ResultCode.BAD_REQUEST,
                    "saleEndTime must be after saleStartTime");
        }
        if (!eventDate.isAfter(saleStart)) {
            throw DomainException.of(ResultCode.BAD_REQUEST,
                    "eventDate must be after saleStartTime");
        }
        if (!eventDate.isAfter(saleEnd)) {
            throw DomainException.of(ResultCode.BAD_REQUEST,
                    "eventDate must be after saleEndTime -- can't sell tickets after event starts");
        }
    }
}
