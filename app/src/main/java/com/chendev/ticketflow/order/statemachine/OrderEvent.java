package com.chendev.ticketflow.order.statemachine;

public enum OrderEvent {
    INITIATE_PAYMENT,

    PAYMENT_SUCCESS,

    // transition target (PAYING→CANCELLED) already works via CANCEL_BY_USER;
    // this label reserved for Stripe webhook to distinguish user-cancel from gateway-reject
    PAYMENT_FAIL,

    PAYMENT_TIMEOUT,

    CANCEL_BY_USER,

    SYSTEM_TIMEOUT
}
