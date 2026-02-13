package com.chendev.ticketflow.user.dto;

import com.chendev.ticketflow.user.entity.UserRole;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class AuthResponse {

    private final Long userId;
    private final String username;
    private final UserRole role;
    private final String accessToken;
    private final long expiresIn;   // milliseconds
}
