package com.chendev.ticketflow.order.statemachine;

public enum OrderStatus {
    CREATED,    // Order placed, awaiting payment
    PAYING,     // Payment initiated
    PAID,       // Payment confirmed
    CANCELLED,  // Cancelled by user, timeout, or payment failure
    CONFIRMED   // Ticket confirmed (auto-confirmed after payment in MVP)
}
