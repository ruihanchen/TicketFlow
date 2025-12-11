package com.chendev.ticketflow.order.controller;

import com.chendev.ticketflow.common.response.Result;
import com.chendev.ticketflow.order.dto.CreateOrderRequest;
import com.chendev.ticketflow.order.dto.OrderResponse;
import com.chendev.ticketflow.order.service.OrderService;
import com.chendev.ticketflow.security.AuthenticatedUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Result<OrderResponse> create(@Valid @RequestBody CreateOrderRequest req) {
        return Result.ok(orderService.createOrder(AuthenticatedUser.getUserId(), req));
    }

    @GetMapping("/{orderNo}")
    public Result<OrderResponse> get(@PathVariable String orderNo) {
        return Result.ok(orderService.getOrder(AuthenticatedUser.getUserId(), orderNo));
    }

    @GetMapping
    public Result<Page<OrderResponse>> list(Pageable pageable) {
        return Result.ok(orderService.getUserOrders(AuthenticatedUser.getUserId(), pageable));
    }

    // Action-based POST: state transitions have side effects; PUT semantics don't fit.
    @PostMapping("/{orderNo}/cancel")
    public Result<OrderResponse> cancel(@PathVariable String orderNo) {
        return Result.ok(orderService.cancelOrder(AuthenticatedUser.getUserId(), orderNo));
    }

    @PostMapping("/{orderNo}/pay")
    public Result<OrderResponse> pay(@PathVariable String orderNo) {
        return Result.ok(orderService.payOrder(AuthenticatedUser.getUserId(), orderNo));
    }

    @PostMapping("/{orderNo}/confirm-payment")
    public Result<OrderResponse> confirmPayment(@PathVariable String orderNo) {
        return Result.ok(orderService.confirmPayment(AuthenticatedUser.getUserId(), orderNo));
    }
}