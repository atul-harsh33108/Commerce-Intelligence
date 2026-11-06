package com.ecommerce.order.security;

import com.ecommerce.order.config.JwtProperties;
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
 * Verifies JSON Web Tokens. It cannot mint them.
 *
 * <p>Only user-service issues tokens; this service holds the same signing key solely to check
 * signatures. There is no {@code generate} method, and adding one would quietly turn the order service
 * into a second identity provider — two places to audit for the price of one convenience.
 *
 * <p>A JWT is three base64url segments joined by dots: header, payload, signature. <strong>The first
 * two are encoded, not encrypted.</strong> Anyone can decode and read them. A JWT protects
 * <em>integrity</em>, not confidentiality: the signature proves the claims have not been altered
 * since the issuer signed them. Never put anything secret in a token.
 *
 * <p>That signature is why the server needs no session store — it recomputes the signature from the
 * payload and its own key, and a match proves authenticity. The flip side is that a token cannot be
 * revoked: until it expires it stays valid even if the user is deleted or demoted. That is the price
 * of statelessness, and the reason expiry is measured in minutes rather than weeks.
 */
@Component
@Slf4j
public class JwtUtil {

    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_USER_ID = "uid";

    private final SecretKey key;
    private final String issuer;

    public JwtUtil(JwtProperties properties) {
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        this.issuer = properties.issuer();
    }

    /**
     * Verifies signature, issuer and expiry, returning the claims if all hold.
     *
     * <p>Returns empty rather than throwing: an invalid token is an ordinary event on a public
     * endpoint — expired, truncated, or simply forged — not an exceptional one. The filter treats
     * the request as anonymous and lets the authorization rules decide the status code.
     */
    public Optional<Claims> parse(String token) {
        try {
            return Optional.of(Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(issuer)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload());
        } catch (JwtException | IllegalArgumentException ex) {
            // Logged without the token: a valid one is a bearer credential, and writing it to the
            // log makes the log as sensitive as a password file.
            log.debug("Rejected JWT: {}: {}", ex.getClass().getSimpleName(), ex.getMessage());
            return Optional.empty();
        }
    }

    public String usernameOf(Claims claims) {
        return claims.getSubject();
    }

    public Long userIdOf(Claims claims) {
        return claims.get(CLAIM_USER_ID, Long.class);
    }

    /**
     * The role claim, as the raw string user-service put there.
     *
     * <p>Deliberately not parsed into an enum. This service does not own the set of roles —
     * user-service does — and copying that enum here would mean the order service crashes in its
     * security filter the day someone adds a role upstream. Returning the string lets an
     * unrecognised role simply match no authorization rule, which is a 403: the safe outcome.
     */
    public String roleOf(Claims claims) {
        return claims.get(CLAIM_ROLE, String.class);
    }
}
