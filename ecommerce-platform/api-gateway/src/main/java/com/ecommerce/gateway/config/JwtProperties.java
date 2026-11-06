package com.ecommerce.gateway.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Binds {@code app.jwt.*}, validated at startup.
 *
 * <p>Validating here rather than at first use means a missing or too-short secret is a startup
 * failure — loud, immediate, and before any traffic — instead of a runtime exception on the first
 * authenticated request to hit the public edge.
 *
 * <p>No {@code expirationMinutes} here, unlike the services: the gateway never mints a token, so how
 * long one lives is not its business. Binding a property a component cannot act on is how a config
 * key ends up with two owners and one of them silently stops mattering.
 */
@ConfigurationProperties(prefix = "app.jwt")
@Validated
public record JwtProperties(

        /**
         * The HMAC signing key. Anyone holding it can mint a token for any user with any role, so it
         * is a credential in the strictest sense.
         *
         * <p>HS256 requires at least 256 bits of key material; the minimum below enforces that in
         * characters. JJWT would reject a shorter key anyway — this just fails earlier with a
         * clearer message.
         */
        @NotBlank(message = "app.jwt.secret must be set (env JWT_SECRET in prod)")
        @Size(min = 32, message = "app.jwt.secret must be at least 32 characters for HS256")
        String secret,

        @NotBlank
        String issuer
) {
}
