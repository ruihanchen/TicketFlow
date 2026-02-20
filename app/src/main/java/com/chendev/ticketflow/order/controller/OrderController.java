package com.chendev.ticketflow.order.controller;

import com.chendev.ticketflow.common.response.PageResult;
import com.chendev.ticketflow.common.response.Result;
import com.chendev.ticketflow.order.dto.CreateOrderRequest;
import com.chendev.ticketflow.order.dto.OrderResponse;
import com.chendev.ticketflow.order.service.OrderService;
import com.chendev.ticketflow.security.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Result<OrderResponse> createOrder(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @Valid @RequestBody CreateOrderRequest request) {
        return Result.success(orderService.createOrder(currentUser.getUserId(), request));
    }

    @PostMapping("/{orderNo}/cancel")
    public Result<OrderResponse> cancelOrder(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @PathVariable String orderNo) {
        return Result.success(
                orderService.cancelOrder(orderNo, currentUser.getUserId()));
    }

    @PostMapping("/{orderNo}/pay")
    public Result<OrderResponse> initiatePayment(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @PathVariable String orderNo) {
        return Result.success(
                orderService.initiatePayment(orderNo, currentUser.getUserId()));
    }

    // In real system this would be called by payment gateway webhook
    // MVP: manual trigger for testing purposes
    @PostMapping("/{orderNo}/confirm-payment")
    public Result<OrderResponse> confirmPayment(@PathVariable String orderNo) {
        return Result.success(orderService.confirmPayment(orderNo));
    }

    @GetMapping("/{orderNo}")
    public Result<OrderResponse> getOrder(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @PathVariable String orderNo) {
        return Result.success(
                orderService.getOrder(orderNo, currentUser.getUserId()));
    }

    @GetMapping
    public Result<PageResult<OrderResponse>> getUserOrders(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        PageRequest pageable = PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return Result.success(
                orderService.getUserOrders(currentUser.getUserId(), pageable));
    }
}
