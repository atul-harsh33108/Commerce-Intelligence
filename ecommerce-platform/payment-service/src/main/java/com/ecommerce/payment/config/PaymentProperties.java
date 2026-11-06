package com.ecommerce.payment.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * The simulated payment gateway's behaviour.
 *
 * <p>Configuration rather than a test double, so the failure path can be exercised against a
 * <em>running</em> system: PAYMENT_FAILURE_RATE=1.0 makes every charge decline, which is how the
 * saga compensation is demonstrated end to end rather than only in a unit test with a mock.
 */
@ConfigurationProperties(prefix = "app.payment")
@Validated
public record PaymentProperties(

        @DecimalMin("0.0") @DecimalMax("1.0")
        double failureRate,

        Duration latency
) {
    public PaymentProperties {
        if (latency == null) {
            latency = Duration.ZERO;
        }
    }
}
