package com.ecommerce.user.dto;

import com.ecommerce.user.entity.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {}

    public record RegisterRequest(
            @NotBlank @Size(min = 3, max = 100) String username,
            @NotBlank @Email @Size(max = 200) String email,
            /*
             * A length floor and nothing else. Composition rules ("one uppercase, one digit, one
             * symbol") are the single most counter-productive password policy in wide use: they
             * shrink the practical search space by pushing everyone to the same substitutions, and
             * NIST SP 800-63B has recommended against them since 2017. Length is what buys entropy.
             */
            @NotBlank @Size(min = 8, max = 72, message = "password must be 8-72 characters")
            String password
    ) {}

    public record LoginRequest(
            @NotBlank String username,
            @NotBlank String password
    ) {}

    /**
     * What a successful login returns.
     *
     * <p>{@code tokenType} and {@code expiresIn} are there so a client does not have to decode the
     * token to know when to stop using it — the alternative is every frontend shipping a JWT parser
     * to read a claim, which is how a frontend ends up trusting claims it has not verified.
     */
    public record LoginResponse(
            String token,
            String tokenType,
            long expiresIn,
            String username,
            String role
    ) {}

    public record UserResponse(
            Long id,
            String username,
            String email,
            String role,
            String customerKey,
            String city,
            String state
    ) {
        public static UserResponse from(User u) {
            return new UserResponse(u.getId(), u.getUsername(), u.getEmail(), u.getRole().name(),
                    u.getCustomerKey(), u.getCity(), u.getState());
        }
    }
}
