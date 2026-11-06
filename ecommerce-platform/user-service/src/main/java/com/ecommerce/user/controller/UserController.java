package com.ecommerce.user.controller;

import com.ecommerce.user.dto.AuthDtos.UserResponse;
import com.ecommerce.user.security.AuthenticatedUser;
import com.ecommerce.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService users;

    /**
     * "Me", identified by the token and by nothing else.
     *
     * <p>There is deliberately no {@code GET /api/users/{id}} that a USER may call. An endpoint that
     * takes an identity in its URL has to check that the caller is allowed to read it, and the check
     * that is never written is the check that is never wrong — IDOR (insecure direct object
     * reference) is consistently in the OWASP top ten precisely because that check is easy to
     * forget. Taking the id from the verified token removes the question.
     */
    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal AuthenticatedUser caller) {
        return users.byUsername(caller.username());
    }

    /** By id, ADMIN only — enforced in SecurityConfig, where every other rule for this path lives. */
    @GetMapping("/{id}")
    public UserResponse byId(@PathVariable Long id) {
        return users.byId(id);
    }
}
