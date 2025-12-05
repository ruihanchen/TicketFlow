package com.chendev.ticketflow.security;

import com.chendev.ticketflow.user.entity.UserRole;

//stored in SecurityContext after JWT validation, controllers can get userId and role without a DB lookup
public record UserPrincipal(Long userId, String username, UserRole role) {
}
