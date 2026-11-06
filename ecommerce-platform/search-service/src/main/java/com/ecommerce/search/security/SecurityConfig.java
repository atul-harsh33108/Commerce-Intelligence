package com.ecommerce.search.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * search-service's filter chain.
 *
 * <p>Search is public for the same reason browsing is: an anonymous visitor has to be able to find a
 * product before deciding to register. There are no write endpoints at all - the index is changed
 * only by consuming events - so there is nothing here that needs a role.
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
                        .requestMatchers(HttpMethod.GET, "/api/search", "/api/search/**").permitAll()

                        // `/error` HAS TO BE PERMITTED, and forgetting it turns every unhandled
                        // exception on a PUBLIC endpoint into a 401.
                        //
                        // Spring forwards an unhandled exception to /error, and that forward is a
                        // fresh dispatch through the filter chain. With no rule for it, `anyRequest()
                        // .authenticated()` applies - so an anonymous caller who triggers a 500 gets
                        // 401 instead, and the response says `"path": "/error"`, which points nowhere
                        // near the endpoint that failed. Seen exactly that way the first time search
                        // was queried before its index existed.
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
