package com.ecommerce.catalog.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * catalog-service's filter chain.
 *
 * <p>Conspicuously missing, compared with user-service's: the {@code AuthenticationManager},
 * {@code UserDetailsService} and {@code PasswordEncoder}. This service has no login endpoint, no
 * access to the users table, and no way to verify a password. It can only decide whether a token
 * somebody else issued is genuine.
 *
 * <p>That is what statelessness buys. Authorization needs the signature and the claims, not a user
 * lookup — so the catalogue can enforce "ADMIN only" without any ability to read, let alone create,
 * a user. The gateway performs the same authentication one hop earlier, and this chain is the
 * defence in depth behind it: curl this service directly on 8082 with a hand-written
 * {@code X-Auth-Role: ADMIN} and it still answers 401, because a signature is what it checks.
 */
@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final SecurityErrorWriter securityErrorWriter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                // Stateless, Bearer-token API: the browser attaches nothing automatically, so CSRF
                // has nothing to ride on. On a cookie-authenticated app this would be a serious
                // mistake.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())

                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(securityErrorWriter)   // 401
                        .accessDeniedHandler(securityErrorWriter))       // 403

                .authorizeHttpRequests(auth -> auth
                        /*
                         * Actuator, split by what each endpoint can do.
                         *
                         * /actuator/health stays open because the thing that calls it cannot carry a
                         * token: a Kubernetes liveness probe has no credentials, and a health check
                         * that answers 401 is a pod that never becomes ready.
                         *
                         * /actuator/prometheus is open for the same reason and with the same caveat:
                         * the boundary is the Service definition, not this filter chain. No service
                         * port is published outside the cluster, so it is reachable from other pods
                         * and nowhere else. The alternative was a scrape token in a ConfigMap, which
                         * is a long-lived credential in plain text - strictly worse than relying on
                         * a network boundary that already exists.
                         *
                         * Everything else needs ADMIN. /actuator/refresh is a POST that re-reads
                         * configuration and rebinds beans in a running process, and /actuator/env
                         * discloses the whole shape of the configuration.
                         */
                        .requestMatchers("/actuator/health", "/actuator/health/**",
                                "/actuator/prometheus").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")

                        // Browsing is public - an anonymous visitor must be able to shop before
                        // deciding to register.
                        .requestMatchers(HttpMethod.GET, "/api/products", "/api/products/*",
                                "/api/products/key/*", "/api/categories").permitAll()

                        // Bulk lookup is a POST because it carries a body, not because it writes.
                        // Public for the same reason the individual reads are: it is the same data.
                        .requestMatchers(HttpMethod.POST, "/api/products/bulk").permitAll()

                        .requestMatchers(HttpMethod.POST, "/api/products/reindex").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/products").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/products/*").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/products/*").hasRole("ADMIN")

                        /*
                         * `/error` HAS TO BE PERMITTED, and forgetting it turns every unhandled
                         * exception into a 401 that names the wrong subsystem.
                         *
                         * Spring forwards an unhandled exception to /error, and that forward is a
                         * FRESH DISPATCH through this whole filter chain. With no rule for it,
                         * `anyRequest().authenticated()` applies, so a 500 on a public endpoint comes
                         * back as `401 "Authentication required"` with `"path": "/error"` in the body
                         * - the wrong status, the wrong reason, and a path the caller never requested.
                         *
                         * This is not hypothetical here. It hid a broken Redis serializer in
                         * catalog-service for the whole of Steps 6-10: every cache HIT threw a
                         * ClassCastException, and every one of them was reported as an authentication
                         * failure on an endpoint that needs no authentication. Added to every service
                         * at once rather than to the one where it was found, because the next
                         * exception will not be in the same service.
                         */
                        .requestMatchers("/error").permitAll()


                        // Deny by default: a route added later is closed until deliberately opened.
                        .anyRequest().authenticated())

                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
