package com.ecommerce.user.service;

import com.ecommerce.user.dto.AuthDtos.LoginRequest;
import com.ecommerce.user.dto.AuthDtos.LoginResponse;
import com.ecommerce.user.dto.AuthDtos.RegisterRequest;
import com.ecommerce.user.dto.AuthDtos.UserResponse;
import com.ecommerce.user.entity.Role;
import com.ecommerce.user.entity.User;
import com.ecommerce.user.exception.DuplicateResourceException;
import com.ecommerce.user.exception.ResourceNotFoundException;
import com.ecommerce.user.repository.UserRepository;
import com.ecommerce.user.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserServiceImpl implements UserService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwt;

    /**
     * A bcrypt hash of a string nobody knows, computed once at class-load.
     *
     * <p>See {@link #login} for why this exists. It is not a credential and cannot be used to
     * authenticate: {@code passwordEncoder.matches} is only ever run against it to burn the same
     * ~100 ms a real comparison costs.
     */
    private static final String DUMMY_HASH =
            "$2a$10$7EqJtq98hPqEX7fNZaFWoOa1H2vB5S2gzE8Vh0e6xN2n1Q3mLpQ3K";

    @Override
    @Transactional
    public UserResponse register(RegisterRequest request) {
        // Checked here for a good error message, and enforced by a unique constraint in the schema
        // because this check races: two simultaneous registrations both see "available" and one hits
        // the constraint. GlobalExceptionHandler turns that into the same 409, so the race is
        // correct rather than merely unlikely.
        if (users.existsByUsername(request.username())) {
            throw new DuplicateResourceException("Username '" + request.username() + "' is taken.");
        }
        if (users.existsByEmail(request.email())) {
            throw new DuplicateResourceException("That email address is already registered.");
        }

        User saved = users.save(User.builder()
                .username(request.username())
                .email(request.email())
                .password(passwordEncoder.encode(request.password()))
                .role(Role.USER)   // never from the request - see the comment in AuthController
                .build());

        log.info("Registered user {} ({})", saved.getId(), saved.getUsername());
        return UserResponse.from(saved);
    }

    /**
     * Verify a password, return a token.
     *
     * <h2>The constant-time shape, which is the only subtle thing here</h2>
     *
     * <p>The obvious implementation returns early when the username is unknown. That leaks: bcrypt
     * at cost 10 takes ~100 ms, so "no such user" answers in about a millisecond and "wrong
     * password" in about a hundred. An attacker with a list of leaked emails can therefore find out
     * which of them have accounts here <em>without ever guessing a password</em> — and the response
     * body says nothing, so the leak survives every attempt to fix it at the message level.
     *
     * <p>Hashing against a dummy digest when the user is absent makes both paths cost the same. It
     * is not perfect — a determined attacker can still find timing differences elsewhere — but it
     * closes the two-orders-of-magnitude one, which is the one that is actually exploitable over a
     * network.
     */
    @Override
    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        User user = users.findByUsername(request.username()).orElse(null);

        if (user == null) {
            passwordEncoder.matches(request.password(), DUMMY_HASH);
            throw new BadCredentialsException("Invalid username or password.");
        }
        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new BadCredentialsException("Invalid username or password.");
        }

        log.info("Issued token for {}", user.getUsername());
        return new LoginResponse(jwt.generate(user), "Bearer", jwt.expirationSeconds(),
                user.getUsername(), user.getRole().name());
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse byUsername(String username) {
        return users.findByUsername(username).map(UserResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("No user named " + username));
    }

    @Override
    @Transactional(readOnly = true)
    public UserResponse byId(Long id) {
        return users.findById(id).map(UserResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("No user with id " + id));
    }
}
