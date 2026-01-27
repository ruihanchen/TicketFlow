package com.chendev.ticketflow.order.statemachine;

public enum OrderEvent {
    INITIATE_PAYMENT,

    PAYMENT_SUCCESS,

    // reserved for payment gateway webhook
    PAYMENT_FAIL,

    PAYMENT_TIMEOUT,

    CANCEL_BY_USER,

    SYSTEM_TIMEOUT
}
