package com.ecommerce.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * How many there are.
 *
 * <p>The counterpart to catalog-service, split from it deliberately (D7): a description changes
 * monthly and is aggressively cacheable, a stock count changes on every order and must never be
 * cached. Putting them in one table would put the hot, contended counter on the same row the read
 * path wants to cache.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableJpaAuditing
// The outbox relay is a @Scheduled poll; without this it is a bean that never runs, and the
// symptom is a saga that stops after this service's step with no error anywhere.
@EnableScheduling
public class InventoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}
