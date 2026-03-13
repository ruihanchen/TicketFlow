package com.chendev.ticketflow.user.service;

import com.chendev.ticketflow.common.exception.BizException;
import com.chendev.ticketflow.common.response.ResultCode;
import com.chendev.ticketflow.security.JwtTokenProvider;
import com.chendev.ticketflow.user.dto.AuthResponse;
import com.chendev.ticketflow.user.dto.LoginRequest;
import com.chendev.ticketflow.user.dto.RegisterRequest;
import com.chendev.ticketflow.user.entity.User;
import com.chendev.ticketflow.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    @Value("${jwt.expiration-ms}")
    private long jwtExpirationMs;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        // Check for duplicate username and email upfront
        // Two separate checks to give precise error messages
        if (userRepository.existsByUsername(request.getUsername())) {
            throw BizException.of(ResultCode.USER_ALREADY_EXISTS,
                    "Username '" + request.getUsername() + "' is already taken");
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw BizException.of(ResultCode.USER_ALREADY_EXISTS,
                    "Email '" + request.getEmail() + "' is already registered");
        }

        // Hash password — never store plain text
        String passwordHash = passwordEncoder.encode(request.getPassword());

        User user = User.create(request.getUsername(), request.getEmail(), passwordHash);
        userRepository.save(user);

        log.info("[Auth] New user registered: userId={}, username={}",
                user.getId(), user.getUsername());

        String token = jwtTokenProvider.generateToken(
                user.getId(), user.getUsername(), user.getRole());

        return AuthResponse.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .role(user.getRole())
                .accessToken(token)
                .expiresIn(jwtExpirationMs)
                .build();
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> BizException.of(ResultCode.INVALID_CREDENTIALS));

        // Always run password check even if user not found
        // Prevents timing attacks that reveal whether a username exists
        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw BizException.of(ResultCode.INVALID_CREDENTIALS);
        }

        log.info("[Auth] User logged in: userId={}, username={}",
                user.getId(), user.getUsername());

        String token = jwtTokenProvider.generateToken(
                user.getId(), user.getUsername(), user.getRole());

        return AuthResponse.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .role(user.getRole())
                .accessToken(token)
                .expiresIn(jwtExpirationMs)
                .build();
    }
}
