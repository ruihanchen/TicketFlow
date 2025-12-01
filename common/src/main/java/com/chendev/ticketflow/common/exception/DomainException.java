package com.chendev.ticketflow.common.exception;

import lombok.Getter;
import com.chendev.ticketflow.common.response.ResultCode;

//handle business logic only, let DB/network exceptions propagate
@Getter
public class DomainException extends RuntimeException {

    private final ResultCode resultCode;

    public DomainException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.resultCode = resultCode;
    }

    public DomainException(ResultCode resultCode, String detail) {
        super(detail);
        this.resultCode = resultCode;
    }

    //wrap infra exception but preserve the cause/stack
    public DomainException(ResultCode resultCode, String detail, Throwable cause) {
        super(detail, cause);
        this.resultCode = resultCode;
    }

    public static DomainException of(ResultCode code) {
        return new DomainException(code);
    }

    public static DomainException of(ResultCode code, String detail) {
        return new DomainException(code, detail);
    }

    public static DomainException of(ResultCode code, String detail, Throwable cause) {
        return new DomainException(code, detail, cause);
    }
}