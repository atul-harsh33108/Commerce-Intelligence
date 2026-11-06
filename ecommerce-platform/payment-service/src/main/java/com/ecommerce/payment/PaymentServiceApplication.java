package com.ecommerce.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Money. Idempotent on the order key, because a retry must never charge twice. */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableJpaAuditing
// The outbox relay is a @Scheduled poll; without this it is a bean that never runs, and the
// symptom is a saga that stops after this service's step with no error anywhere.
@EnableScheduling
public class PaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
