package com.ecommerce.catalog.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * The one topic the catalogue owns.
 *
 * <p>It consumes nothing - no listener, no consumer group, no dead-letter topic. The catalogue is
 * upstream of everything: it announces what a product is and never waits to be told.
 */
@Configuration
public class KafkaConfig {

    /**
     * Keyed by product key, so every change to one product lands on one partition and is applied in
     * order. Without that, a price change and a withdrawal for the same product can be indexed by
     * different consumer threads in either order - and the index ends up showing whichever arrived
     * last rather than whichever happened last.
     */
    @Bean
    public NewTopic productUpdatedTopic(@Value("${app.kafka.topics.product-updated}") String name) {
        return TopicBuilder.name(name).partitions(3).replicas(1).build();
    }
}
