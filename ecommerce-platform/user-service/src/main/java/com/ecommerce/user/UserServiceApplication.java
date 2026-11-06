package com.ecommerce.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Identity, and nothing else.
 *
 * <p><strong>The only service on the platform that can mint a token.</strong> Every other service
 * holds the same signing key and can only verify one — their {@code JwtUtil} has no
 * {@code generate}. That asymmetry is deliberate and is the reason this module exists separately: a
 * component that can issue credentials has to be audited, and there should be exactly one of them.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableJpaAuditing
public class UserServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
