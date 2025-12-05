package com.chendev.ticketflow.user.controller;

import org.springframework.web.bind.annotation.*;
import lombok.RequiredArgsConstructor;
import com.chendev.ticketflow.user.service.AuthService;
import com.chendev.ticketflow.common.response.Result;
import com.chendev.ticketflow.user.dto.AuthResponse;
import com.chendev.ticketflow.user.dto.LoginRequest;
import com.chendev.ticketflow.user.dto.RegisterRequest;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;


@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Result<AuthResponse> register(@Valid @RequestBody RegisterRequest req) {
        return Result.ok(authService.register(req));
    }

    @PostMapping("/login")
    public Result<AuthResponse> login(@Valid @RequestBody LoginRequest req) {
        return Result.ok(authService.login(req));
    }
}
