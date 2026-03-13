package com.chendev.ticketflow.common.exception;

import com.chendev.ticketflow.common.response.ResultCode;

public class BizException extends BaseException{
    public BizException(ResultCode resultCode) {
        super(resultCode);
    }

    public BizException(ResultCode resultCode, String message) {
        super(resultCode, message);
    }

    // Preferred way to throw: throw BizException.of(ResultCode.ORDER_NOT_FOUND)
    public static BizException of(ResultCode resultCode) {
        return new BizException(resultCode);
    }

    // Use when default message needs runtime context
    // e.g. throw BizException.of(ResultCode.ORDER_NOT_FOUND, "Order #" + orderNo + " not found")
    public static BizException of(ResultCode resultCode, String message) {
        return new BizException(resultCode, message);
    }
}
