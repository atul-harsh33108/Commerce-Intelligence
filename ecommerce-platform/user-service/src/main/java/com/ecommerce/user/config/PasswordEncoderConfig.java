package com.ecommerce.user.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The one place a password is turned into a hash.
 *
 * <p>bcrypt at the default strength of 10, which is ~100 ms per hash on this machine. That cost is
 * the <em>feature</em>: it is what makes an offline attack on a stolen table expensive, and it is
 * why a login endpoint is a natural rate-limit target rather than a cheap one. Raising it to 12
 * quadruples both the attacker's cost and the login latency, which is a decision for a deployment
 * rather than a default.
 *
 * <p><strong>bcrypt truncates at 72 bytes.</strong> Anything beyond that is silently ignored — so a
 * 200-character passphrase is no stronger than its first 72 bytes, and (worse) two passwords sharing
 * a 72-byte prefix are the same password. The {@code @Size(max = 72)} on the register DTO makes that
 * a validation error instead of a silent truncation.
 *
 * <p>Not argon2id, and the reason is honest rather than technical: argon2id is the better modern
 * choice, and switching costs a `DelegatingPasswordEncoder` plus a rehash-on-login path. bcrypt with
 * the column sized for a longer digest (see V1__init.sql) leaves that migration open.
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
