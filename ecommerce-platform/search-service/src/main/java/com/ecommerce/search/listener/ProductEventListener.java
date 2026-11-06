package com.ecommerce.search.listener;

import com.ecommerce.search.event.Events;
import com.ecommerce.search.service.ProductIndexer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Keeps the index in step with the catalogue.
 *
 * <p>Thin on purpose: it maps a topic to a method and does no reasoning. Its consumer group is
 * `search-service`, which is what lets it read the same `ecommerce.product.updated` topic as any
 * other consumer without either of them seeing half the events - two consumers in the SAME group
 * split the partitions, and nothing errors when that happens.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProductEventListener {

    private final ProductIndexer indexer;

    @KafkaListener(topics = "${app.kafka.topics.product-updated}",
            groupId = "${app.kafka.groups.search}")
    public void onProductUpdated(Events.ProductUpdated event) {
        indexer.index(event);
    }
}
