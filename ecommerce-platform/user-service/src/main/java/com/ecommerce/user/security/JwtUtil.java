package com.ecommerce.user.security;

import com.ecommerce.user.config.JwtProperties;
import com.ecommerce.user.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/**
 * The platform's only token issuer.
 *
 * <p>Every other service has a copy of this class with {@link #generate} deleted. That is the
 * asymmetry the split is for: five services can check a signature, one can produce one, and the one
 * that can is the only one that needs auditing for credential issuance.
 *
 * <h2>What goes in the token, and what must not</h2>
 *
 * <p>Three claims: the subject (username), {@code role}, and {@code uid}. Together they answer every
 * authorization question the platform asks without a database lookup, which is precisely what makes
 * the services stateless and horizontally scalable.
 *
 * <p><strong>Nothing secret goes in.</strong> A JWT's first two segments are base64url-encoded, not
 * encrypted: paste one into any decoder and the claims are plain text. The signature protects
 * <em>integrity</em> — that the claims have not been altered since this service signed them — and
 * nothing else. The email address is deliberately absent for that reason: it is not needed for
 * authorization, and a token is a string that ends up in logs, browser storage and error reports.
 *
 * <h2>The revocation problem, stated rather than hidden</h2>
 *
 * <p>A signed token cannot be withdrawn. Delete a user or demote an admin and their existing token
 * stays valid until it expires, because verification consults no state. Sixty minutes is the
 * mitigation and it is a trade, not a fix: shorter means more logins, longer means a wider window.
 * The real answer when one is needed is a revocation list — which reintroduces exactly the shared
 * state statelessness removed, and should therefore be added only when something actually requires
 * it.
 */
@Component
@Slf4j
public class JwtUtil {

    private static final String CLAIM_ROLE = "role";

    /**
     * The user's database id.
     *
     * <p>Carried so that services with no users table can still record <em>who</em> did something —
     * order-service stamps it on every order, and it is what makes "my orders" answerable without a
     * cross-service call. The username would have worked and would have been worse: usernames can
     * be changed, ids cannot.
     */
    private static final String CLAIM_USER_ID = "uid";

    private final SecretKey key;
    private final Duration expiration;
    private final String issuer;

    public JwtUtil(JwtProperties properties) {
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
        this.expiration = Duration.ofMinutes(properties.expirationMinutes());
        this.issuer = properties.issuer();
    }

    public String generate(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getUsername())
                .issuer(issuer)
                .claim(CLAIM_ROLE, user.getRole().name())
                .claim(CLAIM_USER_ID, user.getId())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expiration)))
                .signWith(key)
                .compact();
    }

    public Optional<Claims> parse(String token) {
        try {
            return Optional.of(Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(issuer)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload());
        } catch (JwtException | IllegalArgumentException ex) {
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

    public String roleOf(Claims claims) {
        return claims.get(CLAIM_ROLE, String.class);
    }

    public long expirationSeconds() {
        return expiration.toSeconds();
    }
}
