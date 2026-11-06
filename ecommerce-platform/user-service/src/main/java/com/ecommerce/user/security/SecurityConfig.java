package com.ecommerce.user.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * user-service's filter chain.
 *
 * <p>The only one on the platform with public write endpoints, and the reason is unavoidable:
 * registering and logging in cannot require being logged in. That makes these two the platform's
 * only unauthenticated attack surface with a database write behind it, which is why the gateway's
 * rate limiter is applied to {@code /api/auth/**} specifically.
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

                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login")
                            .permitAll()

                        // Declared BEFORE the ADMIN rule for "/api/users/*", because the first match
                        // wins. As it happens "/api/users/*" would match "/api/users/me" too, and an
                        // ADMIN-only "me" is a bug that only shows up for ordinary users.
                        .requestMatchers(HttpMethod.GET, "/api/users/me").hasAnyRole("USER", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/users/*").hasRole("ADMIN")

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
