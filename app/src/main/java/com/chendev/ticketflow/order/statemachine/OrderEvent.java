package com.chendev.ticketflow.order.statemachine;

public enum OrderEvent {
    INITIATE_PAYMENT,   // User initiates payment
    PAYMENT_SUCCESS,    // Payment gateway confirms success
    PAYMENT_FAIL,       // Payment gateway reports failure
    PAYMENT_TIMEOUT,    // Payment window expired
    CANCEL_BY_USER,     // User manually cancels
    SYSTEM_TIMEOUT,     // System cancels due to order expiry
    CONFIRM_TICKET      // Ticket confirmed after payment
}
