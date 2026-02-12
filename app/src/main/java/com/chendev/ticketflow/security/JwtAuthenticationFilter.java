package com.chendev.ticketflow.security;

import com.chendev.ticketflow.user.entity.UserRole;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String token = extractToken(request);

        if (StringUtils.hasText(token) && jwtTokenProvider.validateToken(token)) {
            try {
                Long userId = jwtTokenProvider.extractUserId(token);
                String username = jwtTokenProvider.extractUsername(token);
                UserRole role = jwtTokenProvider.extractRole(token);

                // Build Spring Security's authentication object
                // ROLE_ prefix is Spring Security convention for role-based auth
                SimpleGrantedAuthority authority =
                        new SimpleGrantedAuthority("ROLE_" + role.name());

                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(
                                new AuthenticatedUser(userId, username, role),
                                null,
                                List.of(authority));

                authentication.setDetails(
                        new WebAuthenticationDetailsSource().buildDetails(request));

                // Store in SecurityContext — available for the rest of this request
                SecurityContextHolder.getContext().setAuthentication(authentication);

            } catch (Exception e) {
                // Token was valid but parsing failed — clear context and continue
                // Request will be rejected by Spring Security if endpoint requires auth
                log.warn("[JWT] Failed to set authentication: {}", e.getMessage());
                SecurityContextHolder.clearContext();
            }
        }

        // Always continue the filter chain — let Spring Security decide access
        filterChain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String bearerToken = request.getHeader(AUTHORIZATION_HEADER);
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith(BEARER_PREFIX)) {
            return bearerToken.substring(BEARER_PREFIX.length());
        }
        return null;
    }
}
