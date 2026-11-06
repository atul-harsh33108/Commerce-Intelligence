package com.ecommerce.search.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The events this service consumes. Its own copy, not a shared jar (D2).
 *
 * <p>Narrower than catalog-service's version - it declares only what search needs to index. Jackson
 * ignores unknown properties, so the catalogue can add a field without this service noticing, which
 * is exactly the loose coupling the duplication buys.
 */
public final class Events {

    private Events() {}

    /**
     * A product was created, changed, or withdrawn.
     *
     * <p>ONE event type for all three, and the deactivation case is why: a "ProductDeleted" carrying
     * only an id would force this service to decide what deletion means to an index, and it would
     * decide differently from the warehouse. An event carrying the product's CURRENT STATE lets every
     * consumer apply its own rule to the same fact - search marks it inactive, the warehouse keeps
     * the dimension row. State-carrying events are what make a read model rebuildable from a replay.
     */
    public record ProductUpdated(
            String productKey,
            Long productId,
            String name,
            String category,
            String categoryPt,
            BigDecimal price,
            Integer weightGrams,
            Integer photosQty,
            boolean active,
            Instant occurredAt
    ) {}
}
