package com.chendev.ticketflow.security;

import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import com.chendev.ticketflow.user.entity.UserRole;
import org.springframework.util.Assert;
import java.security.Key;
import java.nio.charset.StandardCharsets;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;

import java.util.Date;
import java.util.Optional;

@Slf4j
@Component
public class JwtTokenProvider {

    private final Key key;
    private final long expirationMs;

    public JwtTokenProvider(@Value("${jwt.secret}") String secret,
                            @Value("${jwt.expiration-ms}") long expirationMs) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        //hs256 requires >= 256-bit secret; fail at startup rather than on first token generation
        Assert.isTrue(keyBytes.length >= 32,
                "jwt.secret must be at least 32 characters (256 bits for HS256)");

        this.key = Keys.hmacShaKeyFor(keyBytes);
        this.expirationMs = expirationMs;
    }

    public String generateToken(Long userId, String username, UserRole role) {
        return Jwts.builder()
                .setSubject(username)
                .claim("userId", userId)
                .claim("role", role.name())
                .setIssuedAt(new Date())
                .setExpiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(key, SignatureAlgorithm.HS256)
                .compact();
    }

    // all token failures collapse to Optional.empty();callers don't need to know why it failed,
    // only whether to trust the token.
    public Optional<UserPrincipal> parseToken(String token) {
        try {
            Claims claims = Jwts.parserBuilder()
                    .setSigningKey(key)
                    .build()
                    .parseClaimsJws(token)
                    .getBody();

            return Optional.of(new UserPrincipal(
                    claims.get("userId", Long.class),
                    claims.getSubject(),
                    UserRole.valueOf(claims.get("role", String.class))));
        } catch (ExpiredJwtException e) {
            log.warn("JWT expired: {}", e.getMessage());
            return Optional.empty();
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("invalid JWT: {}", e.getMessage());
            return Optional.empty();
        }
    }
}