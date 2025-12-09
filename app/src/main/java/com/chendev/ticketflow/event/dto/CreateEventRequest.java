package com.chendev.ticketflow.event.dto;

import lombok.Getter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Getter
public class CreateEventRequest {

    @NotBlank(message = "name is required")
    @Size(max = 200)
    private String name;

    private String description;

    @Size(max = 200)
    private String venue;

    @NotNull(message = "eventDate is required")
    @Future(message = "eventDate must be in the future")
    private Instant eventDate;

    //allow past saleStartTime for immediate activation on publish,validateTimeWindow() ensures
    //valid ranges; isOnSale() covers expiration.
    @NotNull(message = "saleStartTime is required")
    private Instant saleStartTime;

    @NotNull(message = "saleEndTime is required")
    private Instant saleEndTime;

    @NotEmpty(message = "at least one ticket type is required")
    @Valid
    private List<TicketTypeRequest> ticketTypes;

    @Getter
    public static class TicketTypeRequest {

        @NotBlank(message = "ticket type name is required")
        private String name;

        @NotNull
        @DecimalMin(value = "0.01", message = "price must be greater than 0")
        private BigDecimal price;

        @Min(value = 1, message = "stock must be at least 1")
        private int totalStock;
    }
}
