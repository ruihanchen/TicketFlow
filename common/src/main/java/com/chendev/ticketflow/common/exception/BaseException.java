package com.chendev.ticketflow.common.exception;

import com.chendev.ticketflow.common.response.ResultCode;
import lombok.Getter;

@Getter
public class BaseException extends RuntimeException{
    private final int code;
    private final String message;

    protected BaseException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
        this.message = resultCode.getMessage();
    }

    // Allows overriding default message with runtime details
    // e.g. new BizException(ResultCode.ORDER_NOT_FOUND, "Order #12345 not found")
    protected BaseException(ResultCode resultCode, String message) {
        super(message);
        this.code = resultCode.getCode();
        this.message = message;
    }

    // For SystemException only — retains original cause for log tracing
    // e.g. new SystemException(ResultCode.INTERNAL_ERROR, "Redis connection failed", e)
    protected BaseException(ResultCode resultCode, String message, Throwable cause) {
        super(message, cause);
        this.code = resultCode.getCode();
        this.message = message;
    }
}
