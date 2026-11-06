package com.ecommerce.order.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * order-service's filter chain.
 *
 * <p>Nothing here is public. An order is somebody's purchase history; the catalogue is a shop
 * window. The finer-grained rule — "your own orders, not anyone else's" — is enforced in
 * {@code OrderService.assertVisibleTo}, because it depends on the row rather than on the path, and a
 * URL pattern cannot express it.
 */
@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final SecurityErrorWriter securityErrorWriter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())

                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(securityErrorWriter)
                        .accessDeniedHandler(securityErrorWriter))

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**",
                                "/actuator/prometheus").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")

                        // Ordering matters: "/api/orders/me" would otherwise be matched by the
                        // numeric-id rule below, and a customer reading their own orders would get a
                        // 403 that looks like a bug in the token.
                        .requestMatchers(HttpMethod.GET, "/api/orders/me").hasAnyRole("USER", "ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/orders").hasAnyRole("USER", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/orders/*", "/api/orders/key/*")
                            .hasAnyRole("USER", "ADMIN")

                        // Confirmation is a service-to-service action in Step 3's synchronous flow,
                        // performed by payment-service. ADMIN-only, and see OrderController for why
                        // that is a limitation rather than a design.
                        .requestMatchers(HttpMethod.POST, "/api/orders/key/*/confirm").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/orders/key/*/cancel")
                            .hasAnyRole("USER", "ADMIN")

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


                        .anyRequest().authenticated())

                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
