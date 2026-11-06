package com.ecommerce.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;

/**
 * Rate limiting at the edge, keyed by client IP.
 *
 * <h2>Why Redis and not an in-memory counter</h2>
 *
 * <p>An in-process counter limits each gateway <em>instance</em>. Run three and the effective limit
 * is three times what the configuration says — and it changes every time the autoscaler moves. A
 * shared counter in Redis is the only version whose number means anything.
 *
 * <p>Spring Cloud Gateway's {@code RequestRateLimiter} runs a Lua token-bucket script server-side in
 * Redis, so "check and decrement" is one atomic round trip rather than a read-modify-write race
 * between instances.
 *
 * <h2>Keyed by IP, and what that is and is not worth</h2>
 *
 * <p>The endpoint being protected is {@code /api/auth/**}: the platform's only unauthenticated write
 * path, with bcrypt behind it. Bcrypt at cost 10 is ~100 ms of CPU per attempt <em>by design</em>,
 * which makes an unthrottled login endpoint a CPU-exhaustion vector as much as a
 * credential-stuffing one.
 *
 * <p>An IP key is the only thing available before authentication, and it is genuinely weak: NAT puts
 * an office behind one key, and a botnet defeats it entirely. It raises the cost of the naive attack
 * and nothing more. The honest layered answer is per-account lockout after N failures (which needs
 * state user-service owns), plus a WAF or CDN in front. Stating which layer does what matters more
 * than the filter itself.
 *
 * <p><strong>{@code XForwardedRemoteAddressResolver} is deliberately not used.</strong> Trusting
 * {@code X-Forwarded-For} when nothing strips it means a client sets its own key and the limit
 * becomes optional. It is correct only behind a proxy that overwrites the header, and this gateway
 * is the outermost hop locally.
 */
@Configuration
public class RateLimitConfig {

    @Bean
    @Primary
    public KeyResolver clientIpKeyResolver() {
        return exchange -> {
            var remote = exchange.getRequest().getRemoteAddress();
            // "unknown" rather than an exception or a null: a KeyResolver returning empty makes the
            // filter deny the request outright, so an unresolvable address would become a 429 for a
            // caller that did nothing wrong. Bucketing them together is the safer failure.
            return Mono.just(remote == null ? "unknown" : remote.getAddress().getHostAddress());
        };
    }
}
