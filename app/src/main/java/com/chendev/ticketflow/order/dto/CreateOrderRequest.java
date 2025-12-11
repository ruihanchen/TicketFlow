package com.chendev.ticketflow.order.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;

@Getter
public class CreateOrderRequest {

    @NotNull(message = "ticketTypeId is required")
    private Long ticketTypeId;

    // Ticketmaster Verified Fan cap for high-demand events; limits scalper bulk purchases
    @Min(value = 1, message = "quantity must be at least 1")
    @Max(value = 4, message = "quantity cannot exceed 4 per order")
    private int quantity;

    @NotBlank(message = "requestId is required")
    @Size(max = 64, message = "requestId must not exceed 64 characters")
    private String requestId;

    // test helper:bypasses Jackson deserialization;integration tests use this to build requests directly
    public static CreateOrderRequest forTest(Long ticketTypeId, int quantity, String requestId) {
        CreateOrderRequest r = new CreateOrderRequest();
        r.ticketTypeId = ticketTypeId;
        r.quantity = quantity;
        r.requestId = requestId;
        return r;
    }

}