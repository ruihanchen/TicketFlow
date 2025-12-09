package com.chendev.ticketflow.event.dto;

import lombok.Getter;
import java.math.BigDecimal;
import com.chendev.ticketflow.event.entity.TicketType;

@Getter
public class TicketTypeResponse {

    private final Long id;
    private final String name;
    private final BigDecimal price;
    private final Integer totalStock;

    public TicketTypeResponse(TicketType ticketType) {
        this.id = ticketType.getId();
        this.name = ticketType.getName();
        this.price = ticketType.getPrice();
        this.totalStock = ticketType.getTotalStock();
    }
}
