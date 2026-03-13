package com.chendev.ticketflow.common.exception;

import com.chendev.ticketflow.common.response.ResultCode;

public class SystemException extends BaseException{

    public SystemException(ResultCode resultCode) {
        super(resultCode);
    }

    public SystemException(ResultCode resultCode, String message) {
        super(resultCode, message);
    }

    public SystemException(ResultCode resultCode, String message, Throwable cause) {
        super(resultCode, message, cause);
    }

    public static SystemException of(ResultCode resultCode) {
        return new SystemException(resultCode);
    }

    // Always use this when wrapping a caught exception
    // e.g. throw SystemException.of(ResultCode.INTERNAL_ERROR, e)
    public static SystemException of(ResultCode resultCode, Throwable cause) {
        return new SystemException(resultCode, resultCode.getMessage(), cause);
    }
}
