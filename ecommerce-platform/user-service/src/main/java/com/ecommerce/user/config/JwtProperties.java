package com.ecommerce.user.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Binds {@code app.jwt.*}, validated at startup.
 *
 * <p>Validating here rather than at first use means a missing or too-short secret is a startup
 * failure — loud, immediate, and before any traffic — instead of a runtime exception on the first
 * authenticated request.
 */
@ConfigurationProperties(prefix = "app.jwt")
@Validated
public record JwtProperties(

        @NotBlank(message = "app.jwt.secret must be set (env JWT_SECRET in prod)")
        @Size(min = 32, message = "app.jwt.secret must be at least 32 characters for HS256")
        String secret,

        @Positive
        long expirationMinutes,

        @NotBlank
        String issuer
) {
}
