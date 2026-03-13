package com.chendev.ticketflow.common.exception;

import com.chendev.ticketflow.common.response.Result;
import com.chendev.ticketflow.common.response.ResultCode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // Business exceptions — expected, user-side issues
    // Log WARN without stack trace: not our fault, not immediately actionable
    @ExceptionHandler(BizException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleBizException(BizException e) {
        log.warn("[BizException] code={}, message={}", e.getCode(), e.getMessage());
        return Result.failure(e.getCode(), e.getMessage());
    }

    // System exceptions — unexpected infrastructure failures
    // Log ERROR with full stack trace: needs immediate attention
    // Return generic message: never expose internal details to frontend
    @ExceptionHandler(SystemException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleSystemException(SystemException e) {
        log.error("[SystemException] code={}, message={}", e.getCode(), e.getMessage(), e);
        return Result.failure(ResultCode.INTERNAL_ERROR);
    }

    // Framework-level validation: @RequestBody + @Valid
    // Not a business error — use HTTP status code directly, no ResultCode needed
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("[ValidationException] {}", message);
        return Result.failure(HttpStatus.BAD_REQUEST.value(), message);
    }

    // Framework-level validation: @PathVariable + @RequestParam
    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleConstraintViolationException(ConstraintViolationException e) {
        String message = e.getConstraintViolations()
                .stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("; "));
        log.warn("[ConstraintViolationException] {}", message);
        return Result.failure(HttpStatus.BAD_REQUEST.value(), message);
    }

    // Last resort fallback — should rarely fire in practice
    // If this triggers frequently, exception handling upstream needs review
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleUnexpectedException(Exception e) {
        log.error("[UnexpectedException] Unexpected error occurred", e);
        return Result.failure(ResultCode.INTERNAL_ERROR);
    }
}
