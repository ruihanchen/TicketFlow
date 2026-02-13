package com.chendev.ticketflow.user.controller;

import com.chendev.ticketflow.common.response.Result;
import com.chendev.ticketflow.user.dto.AuthResponse;
import com.chendev.ticketflow.user.dto.LoginRequest;
import com.chendev.ticketflow.user.dto.RegisterRequest;
import com.chendev.ticketflow.user.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Result<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return Result.success(authService.register(request));
    }

    @PostMapping("/login")
    public Result<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.success(authService.login(request));
    }
}
