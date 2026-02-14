package com.chendev.ticketflow.event.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Getter
public class CreateEventRequest {

    @NotBlank(message = "Event name is required")
    @Size(max = 200, message = "Event name must not exceed 200 characters")
    private String name;

    private String description;

    @Size(max = 200, message = "Venue must not exceed 200 characters")
    private String venue;

    @NotNull(message = "Event date is required")
    @Future(message = "Event date must be in the future")
    private LocalDateTime eventDate;

    @NotNull(message = "Sale start time is required")
    private LocalDateTime saleStartTime;

    @NotNull(message = "Sale end time is required")
    private LocalDateTime saleEndTime;

    @NotEmpty(message = "At least one ticket type is required")
    @Valid
    private List<TicketTypeRequest> ticketTypes;

    @Getter
    public static class TicketTypeRequest {

        @NotBlank(message = "Ticket type name is required")
        @Size(max = 100)
        private String name;

        @NotNull(message = "Price is required")
        @DecimalMin(value = "0.01", message = "Price must be greater than 0")
        private BigDecimal price;

        @NotNull(message = "Stock is required")
        @Min(value = 1, message = "Stock must be at least 1")
        private Integer totalStock;
    }
}
