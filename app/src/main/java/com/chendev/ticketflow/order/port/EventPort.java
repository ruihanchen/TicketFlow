package com.chendev.ticketflow.order.port;

//order domain's contract with Event domain.
public interface EventPort {
    // throws DomainException(TICKET_TYPE_NOT_FOUND) if not found
    TicketTypeInfo getTicketTypeInfo(Long ticketTypeId);
}
