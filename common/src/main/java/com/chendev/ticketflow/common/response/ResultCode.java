package com.chendev.ticketflow.common.response;

import lombok.Getter;

@Getter
public enum ResultCode {

    //using 0 instead of 200 to separate app logic from HTTP status. Non-zero indicates error
    SUCCESS(0, "ok", 200),

    //standard HTTP mappings
    BAD_REQUEST(400, "bad request", 400),
    UNAUTHORIZED(401, "unauthorized", 401),
    FORBIDDEN(403, "forbidden", 403),
    NOT_FOUND(404, "not found", 404),
    CONFLICT(409, "conflict", 409),
    INTERNAL_ERROR(500, "internal server error", 500),

    //order
    //domain codes let clients distinguish errors that share the same HTTP status
    ORDER_NOT_FOUND(1001, "order not found", 404),
    INVALID_STATE_TRANSITION(1003, "invalid state transition", 409),
    DUPLICATE_REQUEST(1004, "duplicate request", 409),

    // inventory
    INSUFFICIENT_STOCK(2001, "insufficient stock", 409),
    INVENTORY_NOT_FOUND(2002, "inventory not found", 404),
    INVENTORY_LOCK_FAILED(2003, "high demand, please retry", 503),

    // event
    EVENT_NOT_FOUND(3001, "event not found", 404),
    TICKET_TYPE_NOT_FOUND(3002, "ticket type not found", 404),
    EVENT_NOT_ON_SALE(3003, "event is not currently on sale", 409),

    // auth
    USER_ALREADY_EXISTS(4002, "user already exists", 409),
    INVALID_CREDENTIALS(4003, "invalid username or password", 401),
    TOKEN_INVALID(4004, "token invalid or expired", 401);

    private final int code;
    private final String message;
    //uses int (not HttpStatus) to keep common Spring-free;ResultCodeTest handles whitelist validation
    private final int httpStatus;

    ResultCode(int code, String message, int httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }
}