package com.ecommerce.search;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Product search: a READ MODEL fed by events, owning no source of truth.
 *
 * <p>The index can be deleted at any moment and rebuilt from the catalogue. That is not a caveat -
 * it is the property that makes eventual consistency acceptable here. A stale search result is a
 * cosmetic problem for seconds; a stale price on an order is money.
 *
 * <p>Note what is absent: no JPA, no database, no Flyway. This service has no table of its own and
 * cannot write anything anybody else reads.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class SearchServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SearchServiceApplication.class, args);
    }
}
