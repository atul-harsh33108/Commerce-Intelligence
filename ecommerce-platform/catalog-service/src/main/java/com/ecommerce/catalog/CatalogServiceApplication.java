package com.ecommerce.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The product catalogue: what a product <em>is</em>.
 *
 * <p>Not how many there are. That lives in inventory-service, in a different database, for the
 * reasons in docs/decisions.md D7 — a description changes monthly and is aggressively cacheable, a
 * stock count changes on every order and must never be cached.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableJpaAuditing
// The outbox relay is a @Scheduled poll. Without this the events are written and never
// published, and search silently stops updating while everything reports healthy.
@EnableScheduling
public class CatalogServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CatalogServiceApplication.class, args);
    }
}
