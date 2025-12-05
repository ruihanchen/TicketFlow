package com.chendev.ticketflow.user.dto;

import lombok.Getter;
import jakarta.validation.constraints.NotBlank;

@Getter
public class LoginRequest {

    @NotBlank(message = "username is required")
    private String username;

    @NotBlank(message = "password is required")
    private String password;
}
