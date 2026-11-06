package com.ecommerce.user.controller;

import com.ecommerce.user.dto.AuthDtos.LoginRequest;
import com.ecommerce.user.dto.AuthDtos.LoginResponse;
import com.ecommerce.user.dto.AuthDtos.RegisterRequest;
import com.ecommerce.user.dto.AuthDtos.UserResponse;
import com.ecommerce.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Register and log in. The only two endpoints on the platform that must work without a token.
 *
 * <p><strong>Note what {@code RegisterRequest} does not have: a {@code role} field.</strong> Every
 * registration creates a USER, in {@code UserServiceImpl}, unconditionally. A role that arrives in
 * the request body is a privilege-escalation endpoint wearing a DTO — and it is a genuinely common
 * mistake, because binding the whole entity is the shortest code. Promoting somebody to ADMIN is a
 * deliberate act performed against the database or through an admin-only endpoint, not something a
 * self-service form can do.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService users;

    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(users.register(request));
    }

    /**
     * POST, not GET, and the credentials are in the body rather than the query string.
     *
     * <p>Query strings are logged by every proxy, load balancer and browser history on the path.
     * A password in a URL is a password in a log file.
     */
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return users.login(request);
    }
}
