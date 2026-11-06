package com.ecommerce.gateway.security;

import com.ecommerce.gateway.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Verifies signatures. Cannot produce one.
 *
 * <p>It holds the signing key solely to check that a token was issued by user-service and has not
 * expired — there is no {@code generate}, no users table, no password encoder and no login route
 * anywhere in this module. A component that can mint credentials is a component that has to be
 * audited, and this one is the piece of the platform exposed to the public internet.
 *
 * <p>No {@code Role} enum either: an unrecognised role must not throw inside the edge filter. It
 * travels as a string, and an unknown one simply fails whatever authorization rule it is later
 * measured against, inside the service that owns that rule.
 */
@Component
@Slf4j
public class GatewayJwtVerifier {

    private final SecretKey key;
    private final String issuer;

    public GatewayJwtVerifier(JwtProperties properties) {
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        this.issuer = properties.issuer();
    }

    /**
     * Returns the claims if the token is genuine, empty otherwise.
     *
     * <p>Empty rather than an exception: an invalid token on a public edge is an ordinary event —
     * expired, truncated, copied from a stale tab, or forged — and the filter's job is to answer
     * 401, not to handle a surprise.
     */
    public Optional<Claims> verify(String token) {
        try {
            return Optional.of(Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(issuer)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload());
        } catch (JwtException | IllegalArgumentException ex) {
            // Without the token itself. A valid one is a bearer credential, and a log containing
            // them is as sensitive as a password file — more so here, where every request in the
            // platform passes through and the log is therefore complete.
            log.debug("Rejected JWT at the edge: {}: {}",
                    ex.getClass().getSimpleName(), ex.getMessage());
            return Optional.empty();
        }
    }
}
