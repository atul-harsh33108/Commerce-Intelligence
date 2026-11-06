package com.ecommerce.order.client;

import feign.RequestInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Carries the caller's token onward to the next service.
 *
 * <p>Without this, order-service calls inventory-service anonymously and gets a 401 — because
 * inventory-service verifies signatures rather than trusting the network, which is the whole point
 * of the gateway design (see {@code EdgeAuthenticationFilter}). The token is the identity; a call
 * that drops it is a call from nobody.
 *
 * <h2>What this is not</h2>
 *
 * <p>It is not a service account. The downstream call is made <em>as the user</em>, so
 * inventory-service's "USER or ADMIN may reserve" rule is evaluated against the person who actually
 * placed the order — and an ADMIN-only action cannot be performed by a USER simply because a service
 * relayed it. That is the correct default, and it has a real limit: background work with no user in
 * scope (the saga recovery job) has no token to propagate. Rather than inventing a super-user
 * credential here, that path calls the local service bean directly and never crosses the network —
 * see {@code OrderRecoveryJob}.
 *
 * <h2>The thread-local caveat</h2>
 *
 * <p>{@code RequestContextHolder} is thread-bound. Any call made from a {@code @Async} method, a
 * scheduled task, or a Kafka listener has no request attributes and gets no header — silently. That
 * is why the check below is defensive rather than assuming a request exists, and why the saga's
 * event-driven path does not use Feign at all.
 */
public class FeignAuthPropagation {

    @Bean
    public RequestInterceptor bearerTokenRelay() {
        return template -> {
            var attributes = RequestContextHolder.getRequestAttributes();
            if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
                return;
            }
            HttpServletRequest request = servletAttributes.getRequest();
            String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (authorization != null && !authorization.isBlank()) {
                template.header(HttpHeaders.AUTHORIZATION, authorization);
            }
        };
    }
}
