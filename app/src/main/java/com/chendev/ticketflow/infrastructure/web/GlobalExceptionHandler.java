package com.chendev.ticketflow.infrastructure.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

import com.chendev.ticketflow.common.exception.DomainException;
import com.chendev.ticketflow.common.response.Result;
import com.chendev.ticketflow.common.response.ResultCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;

import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;


import java.util.stream.Collectors;

//web-tier exception mapping. lives here to keep 'common' free of Spring Web/Security.
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // Domain errors: status and code derived from Business Exception
    @ExceptionHandler(DomainException.class)
    public ResponseEntity<Result<?>> handleDomainException(DomainException e) {
        ResultCode rc = e.getResultCode();
        log.warn("[DomainException] code={}, msg={}", rc, e.getMessage());
        return ResponseEntity
                .status(rc.getHttpStatus())
                .body(Result.fail(rc, e.getMessage()));
    }

    // without this, @PreAuthorize failures return bare 403 with no body
    @ExceptionHandler(AccessDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Result<?> handleAccessDenied(AccessDeniedException e) {
        log.warn("[AccessDenied] {}", e.getMessage());
        return Result.fail(ResultCode.FORBIDDEN);
    }

    // fallback for races that bypass service-layer duplicate checks
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Result<?> handleDataIntegrityViolation(DataIntegrityViolationException e) {
        log.warn("[DataIntegrityViolation] {}", e.getMostSpecificCause().getMessage());
        return Result.fail(ResultCode.CONFLICT, "duplicate or constraint violation");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<?> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return Result.fail(ResultCode.BAD_REQUEST, detail);
    }

    // malformed JSON falls here (without this it returns 500)
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<?> handleUnreadableMessage(HttpMessageNotReadableException e) {
        log.warn("[BadRequest] unreadable body: {}", e.getMostSpecificCause().getMessage());
        return Result.fail(ResultCode.BAD_REQUEST, "malformed request body");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public Result<?> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return Result.fail(ResultCode.BAD_REQUEST,
                "method " + e.getMethod() + " not supported");
    }


    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<?> handleMissingParam(MissingServletRequestParameterException e) {
        return Result.fail(ResultCode.BAD_REQUEST, e.getMessage());
    }

    // non-numeric path variables fall through to handleUnexpected() and return 500 without this,
    // correct mapping is 400, input never reached domain logic.
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<?> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String requiredType = e.getRequiredType() != null
                ? e.getRequiredType().getSimpleName() : "expected type";
        String detail = String.format("invalid value for parameter '%s': expected %s",
                e.getName(), requiredType);
        log.warn("[BadRequest] type mismatch: {}", detail);
        return Result.fail(ResultCode.BAD_REQUEST, detail);
    }

    // Order @Version conflict: two concurrent requests modify the same order(user double-clicks pay,
    // or cancelOrder races with the reaper). Not a server error; the first request won, this one lost.
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Result<?> handleOptimisticLock(ObjectOptimisticLockingFailureException e) {
        log.warn("[OptimisticLock] {}", e.getMessage());
        return Result.fail(ResultCode.CONFLICT, "concurrent modification, please retry");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<?> handleUnexpected(Exception e) {
        log.error("[UnexpectedException] type={}, msg={}",
                e.getClass().getSimpleName(), e.getMessage(), e);
        return Result.fail(ResultCode.INTERNAL_ERROR);
    }
}