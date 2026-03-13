package com.chendev.ticketflow.event.dto;

import com.chendev.ticketflow.event.entity.TicketType;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class TicketTypeResponse {

    private Long id;
    private String name;
    private BigDecimal price;
    private Integer totalStock;
    private Integer availableStock;  // Fetched from Inventory

    public static TicketTypeResponse from(TicketType ticketType, Integer availableStock) {
        return TicketTypeResponse.builder()
                .id(ticketType.getId())
                .name(ticketType.getName())
                .price(ticketType.getPrice())
                .totalStock(ticketType.getTotalStock())
                .availableStock(availableStock)
                .build();
    }
}
