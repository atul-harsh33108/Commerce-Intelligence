package com.ecommerce.inventory.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * inventory-service's filter chain.
 *
 * <p>Stock levels are readable by anyone — a product page says "3 left" to a visitor who has not
 * logged in. Everything that <em>changes</em> a level needs a real identity, because a reservation
 * takes a unit out of circulation and an anonymous caller could empty the warehouse with a loop.
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

                        .requestMatchers(HttpMethod.GET, "/api/inventory", "/api/inventory/*")
                            .permitAll()

                        // Both roles named explicitly: Spring Security roles are flat, so
                        // hasRole("USER") alone would give an ADMIN a 403 here.
                        .requestMatchers(HttpMethod.POST, "/api/inventory/reserve",
                                "/api/inventory/release/*", "/api/inventory/confirm/*")
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
