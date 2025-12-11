package com.chendev.ticketflow.order.port;

import java.math.BigDecimal;

// value object that everything OrderService needs from the Event domain.
// keeps Order domain blind to Event internals
public record TicketTypeInfo(
        Long ticketTypeId,

        Long eventId,

        BigDecimal price,

        boolean onSale
) {
}