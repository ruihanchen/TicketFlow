package com.chendev.ticketflow;

import com.chendev.ticketflow.order.dto.CreateOrderRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

// Jackson deserialization exercises Bean Validation the same way @Valid does at the controller layer.
public final class OrderTestFactory {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OrderTestFactory() {}

    public static CreateOrderRequest createRequest(Long ticketTypeId, int quantity, String requestId) {
        try {
            String json = String.format(
                    "{\"ticketTypeId\":%d,\"quantity\":%d,\"requestId\":\"%s\"}",
                    ticketTypeId, quantity, requestId);
            return MAPPER.readValue(json, CreateOrderRequest.class);
        } catch (Exception e) {
            throw new RuntimeException("failed to build CreateOrderRequest", e);
        }
    }
}
