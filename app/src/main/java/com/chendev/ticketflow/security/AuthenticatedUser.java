package com.chendev.ticketflow.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import com.chendev.ticketflow.common.exception.DomainException;
import com.chendev.ticketflow.common.response.ResultCode;

//userId and role come from the JWT claims already in SecurityContext;no DB lookup needed.
public class AuthenticatedUser {

    public static UserPrincipal get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof UserPrincipal)) {
            throw DomainException.of(ResultCode.UNAUTHORIZED);
        }
        return (UserPrincipal) auth.getPrincipal();
    }

    public static Long getUserId() {
        return get().userId();
    }

    public static String getUsername() {
        return get().username();
    }
}