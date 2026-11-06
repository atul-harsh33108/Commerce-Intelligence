package com.ecommerce.search.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Turns a valid {@code Authorization: Bearer <token>} header into an authenticated SecurityContext.
 *
 * <p><strong>It never rejects anything.</strong> A missing, malformed, or expired token simply
 * leaves the context empty and the request continues as anonymous — and the authorization rules then
 * decide: 401 on a protected route, 200 on a public one. Rejecting here would break every public
 * endpoint for anyone whose token happens to have expired, which on a search service that anonymous
 * visitors are supposed to browse is the whole front page.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        bearerToken(request)
                .flatMap(jwtUtil::parse)
                .ifPresent(claims -> {
                    /*
                     * The identity is built from the token's own claims — no database lookup.
                     *
                     * That is the point of a stateless token: any instance can serve any request
                     * with no shared session store, which is what makes horizontal scaling and the
                     * gateway possible.
                     *
                     * The cost is staleness. A user deleted or demoted keeps whatever the token says
                     * until it expires. Re-reading the user here would close that window at the
                     * price of a query on every single request — and would not work at the gateway,
                     * which has no database. Short expiry is the mitigation.
                     */
                    var authorities = List.of(
                            new SimpleGrantedAuthority("ROLE_" + jwtUtil.roleOf(claims)));

                    var authentication = new UsernamePasswordAuthenticationToken(
                            new AuthenticatedUser(jwtUtil.userIdOf(claims),
                                    jwtUtil.usernameOf(claims),
                                    jwtUtil.roleOf(claims)),
                            null, authorities);
                    authentication.setDetails(
                            new WebAuthenticationDetailsSource().buildDetails(request));

                    SecurityContextHolder.getContext().setAuthentication(authentication);
                });

        filterChain.doFilter(request, response);
    }

    private static Optional<String> bearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return Optional.empty();
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }
}
