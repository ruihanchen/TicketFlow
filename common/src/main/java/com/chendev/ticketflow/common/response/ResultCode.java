package com.chendev.ticketflow.common.response;

import lombok.Getter;

@Getter
public enum ResultCode {
    // ─── Success ─────────────────────────────────────────────────────────────
    SUCCESS(200, "Success"),

    // ─── User / Authentication [20001 - 20099] ────────────────────────────────
    USER_NOT_FOUND(20001, "User not found"),
    USER_ALREADY_EXISTS(20002, "User already exists"),
    INVALID_CREDENTIALS(20003, "Invalid username or password"),
    TOKEN_INVALID(20004, "Token is invalid"),
    TOKEN_EXPIRED(20005, "Token has expired"),
    TOKEN_MISSING(20006, "Token is missing"),

    // ─── Event / Ticket [30001 - 30099] ──────────────────────────────────────
    EVENT_NOT_FOUND(30001, "Event not found"),
    EVENT_NOT_AVAILABLE(30002, "Event is not available for purchase"),
    TICKET_TYPE_NOT_FOUND(30003, "Ticket type not found"),
    INVENTORY_INSUFFICIENT(30004, "Tickets are sold out"),
    INVENTORY_LOCK_FAILED(30005, "Insufficient inventory for requested quantity"),
    PURCHASE_LIMIT_EXCEEDED(30006, "Purchase limit per user exceeded"),

    // ─── Order [40001 - 40099] ────────────────────────────────────────────────
    ORDER_NOT_FOUND(40001, "Order not found"),
    ORDER_CREATE_FAILED(40002, "Failed to create order"),
    ORDER_STATUS_INVALID(40003, "Invalid order status transition"),
    ORDER_ALREADY_PAID(40004, "Order has already been paid"),
    ORDER_EXPIRED(40005, "Order has expired"),
    ORDER_CANCEL_NOT_ALLOWED(40006, "Order cannot be cancelled at current status"),
    IDEMPOTENT_REJECTION(40007, "Duplicate request, please wait"),

    // ─── Payment [50001 - 50099] ──────────────────────────────────────────────
    PAYMENT_FAILED(50001, "Payment processing failed"),
    PAYMENT_TIMEOUT(50002, "Payment timed out"),
    PAYMENT_ALREADY_PROCESSED(50003, "Payment has already been processed"),

    // ─── System [99001 - 99099] ───────────────────────────────────────────────
    INTERNAL_ERROR(99001, "Internal server error"),
    SERVICE_UNAVAILABLE(99002, "Service temporarily unavailable");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
