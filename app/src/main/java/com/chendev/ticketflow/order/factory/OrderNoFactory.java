package com.chendev.ticketflow.order.factory;

import com.github.f4b6a3.uuid.UuidCreator;
import org.springframework.stereotype.Component;

// UUIDv7: 48-bit ms timestamp prefix (B-tree friendly, time-sortable) + 74 bits of random.
// Extracted from OrderService so the scheme is swappable in one file and testable without Spring.
@Component
public class OrderNoFactory {

    // TF- prefix: tells you it's an order ID without a lookup, same as Stripe's pi_/cus_ convention
    private static final String PREFIX = "TF-";

    public String create() {
        // strip dashes for a compact prefix-friendly format; canonical UUID form is 8-4-4-4-12
        return PREFIX + UuidCreator.getTimeOrderedEpoch().toString().replace("-", "");
    }
}