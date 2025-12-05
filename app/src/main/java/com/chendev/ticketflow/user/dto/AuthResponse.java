package com.chendev.ticketflow.user.dto;

import lombok.Getter;
import lombok.Builder;
import com.chendev.ticketflow.user.entity.UserRole;

@Getter
@Builder
public class AuthResponse {
    private final Long userId;

    private final String username;

    private final UserRole role;

    private final String accessToken;

    private final long expiresIn;  // ms
}
