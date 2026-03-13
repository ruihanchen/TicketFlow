package com.chendev.ticketflow.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;

import java.io.Serializable;
import java.time.Instant;

@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Result<T> implements Serializable {

    private final int code;
    private final String message;
    private final T data;
    private final long timestamp;
    private String requestId;

    private Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.timestamp = Instant.now().toEpochMilli();
    }

    // ─── Success ──────────────────────────────────────────────────────────────

    public static <T> Result<T> success() {
        return new Result<>(
                ResultCode.SUCCESS.getCode(),
                ResultCode.SUCCESS.getMessage(),
                null);
    }

    public static <T> Result<T> success(T data) {
        return new Result<>(
                ResultCode.SUCCESS.getCode(),
                ResultCode.SUCCESS.getMessage(),
                data);
    }

    // ─── Failure ──────────────────────────────────────────────────────────────

    // Used by GlobalExceptionHandler for BizException / SystemException
    public static <T> Result<T> failure(ResultCode resultCode) {
        return new Result<>(
                resultCode.getCode(),
                resultCode.getMessage(),
                null);
    }

    // Used when ResultCode message needs to be overridden with runtime details
    // e.g. "Only 3 tickets remaining" instead of generic "Tickets are sold out"
    public static <T> Result<T> failure(ResultCode resultCode, String customMessage) {
        return new Result<>(
                resultCode.getCode(),
                customMessage,
                null);
    }

    // Used by GlobalExceptionHandler for framework-level errors (validation, etc.)
    // These are not business errors, so they don't need a ResultCode
    public static <T> Result<T> failure(int code, String message) {
        return new Result<>(code, message, null);
    }

    // ─── Utility ──────────────────────────────────────────────────────────────

    public boolean isSuccess() {
        return this.code == ResultCode.SUCCESS.getCode();
    }

    // Method chaining — allows: Result.failure(...).withRequestId("abc")
    // Called by AOP or GlobalExceptionHandler to inject correlation ID
    public Result<T> withRequestId(String requestId) {
        this.requestId = requestId;
        return this;
    }
}
