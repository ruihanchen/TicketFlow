package com.chendev.ticketflow.user.service;

import com.chendev.ticketflow.common.exception.DomainException;
import com.chendev.ticketflow.common.response.ResultCode;
import com.chendev.ticketflow.security.JwtTokenProvider;
import com.chendev.ticketflow.user.dto.AuthResponse;
import com.chendev.ticketflow.user.dto.LoginRequest;
import com.chendev.ticketflow.user.dto.RegisterRequest;
import com.chendev.ticketflow.user.entity.User;
import com.chendev.ticketflow.user.repository.UserRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
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

    //constant-time auth path; prevents timing leaks for missing users.
    private String timingSafeHash;

    @PostConstruct
    void initTimingSafeHash() {
        timingSafeHash = passwordEncoder.encode("timing-attack-prevention");
    }

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        if (userRepository.existsByUsername(req.getUsername())) {
            throw DomainException.of(ResultCode.USER_ALREADY_EXISTS,
                    "username '" + req.getUsername() + "' is already taken");
        }
        if (userRepository.existsByEmail(req.getEmail())) {
            throw DomainException.of(ResultCode.USER_ALREADY_EXISTS,
                    "email already registered");
        }

        User user = User.create(req.getUsername(), req.getEmail(),
                passwordEncoder.encode(req.getPassword()));

        try {
            userRepository.save(user);
            userRepository.flush();
        } catch (DataIntegrityViolationException e) {
            //two concurrent registrations can both pass the existsBy checks above, then race to INSERT.
            //DB unique constraint catches the second one; flush() ensures it fires here, not at commit.
            throw DomainException.of(ResultCode.USER_ALREADY_EXISTS,
                    "username or email already taken", e);
        }

        log.info("[Auth] registered: userId={}, username={}", user.getId(), user.getUsername());
        return buildAuthResponse(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest req) {
        User user = userRepository.findByUsername(req.getUsername()).orElse(null);
        // matches() must run before checking user == null, short-circuiting would make the missing username
        // path faster and leak whether a username is registered.
        boolean passwordValid = passwordEncoder.matches(
                req.getPassword(),
                user != null ? user.getPasswordHash() : timingSafeHash);

        if (user == null || !passwordValid) {
            throw DomainException.of(ResultCode.INVALID_CREDENTIALS);
        }

        log.info("[Auth] login: userId={}, username={}", user.getId(), user.getUsername());
        return buildAuthResponse(user);
    }

    private AuthResponse buildAuthResponse(User user) {
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