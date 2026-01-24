package com.chendev.ticketflow.infrastructure.adapter;

import com.chendev.ticketflow.common.exception.DomainException;
import com.chendev.ticketflow.common.response.ResultCode;
import com.chendev.ticketflow.event.repository.TicketTypeRepository;
import com.chendev.ticketflow.order.port.EventPort;
import com.chendev.ticketflow.order.port.TicketTypeInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// Bridges Order domain → Event domain for ticket-type lookups.
// Hits TicketTypeRepository directly, no reason to route through EventService.
@Component
@RequiredArgsConstructor
public class EventAdapter implements EventPort {

    private final TicketTypeRepository ticketTypeRepository;

    @Override
    public TicketTypeInfo getTicketTypeInfo(Long ticketTypeId) {
        return ticketTypeRepository.findByIdWithEvent(ticketTypeId)
                .map(tt -> new TicketTypeInfo(
                        tt.getId(),
                        tt.getEvent().getId(),
                        tt.getPrice(),
                        tt.getEvent().isOnSale()
                ))
                .orElseThrow(() -> DomainException.of(ResultCode.TICKET_TYPE_NOT_FOUND));
    }
}