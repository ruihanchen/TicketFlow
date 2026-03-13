package com.chendev.ticketflow.security;

import com.chendev.ticketflow.user.entity.UserRole;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Slf4j
@Component
public class JwtTokenProvider {

    private final SecretKey secretKey;
    private final long expirationMs;

    public JwtTokenProvider(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration-ms}") long expirationMs) {
        // Derive a cryptographically safe key from the configured secret string
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    public String generateToken(Long userId, String username, UserRole role) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .claim("role", role.name())
                .issuedAt(now)
                .expiration(expiry)
                .signWith(secretKey)
                .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.warn("[JWT] Token expired: {}", e.getMessage());
        } catch (UnsupportedJwtException e) {
            log.warn("[JWT] Unsupported token: {}", e.getMessage());
        } catch (MalformedJwtException e) {
            log.warn("[JWT] Malformed token: {}", e.getMessage());
        } catch (SecurityException e) {
            log.warn("[JWT] Invalid signature: {}", e.getMessage());
        } catch (IllegalArgumentException e) {
            log.warn("[JWT] Empty or null token: {}", e.getMessage());
        }
        return false;
    }

    public Long extractUserId(String token) {
        return Long.valueOf(parseToken(token).getSubject());
    }

    public String extractUsername(String token) {
        return parseToken(token).get("username", String.class);
    }

    public UserRole extractRole(String token) {
        return UserRole.valueOf(parseToken(token).get("role", String.class));
    }
}
