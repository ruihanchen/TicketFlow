package com.chendev.ticketflow.security;

import com.chendev.ticketflow.user.entity.UserRole;
import lombok.AllArgsConstructor;
import lombok.Getter;

// Represents the authenticated principal stored in SecurityContext
// Accessible anywhere via SecurityContextHolder
@Getter
@AllArgsConstructor
public class AuthenticatedUser {
    private final Long userId;
    private final String username;
    private final UserRole role;
}