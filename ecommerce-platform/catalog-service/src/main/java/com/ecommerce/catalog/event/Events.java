package com.ecommerce.catalog.event;

import java.math.BigDecimal;
import java.time.Instant;

/** What the catalogue announces. Its own copy, not a shared jar (D2). */
public final class Events {

    private Events() {}

    /**
     * A product was created, changed, or withdrawn - ONE event for all three.
     *
     * <p>A "ProductDeleted" carrying only an id would force every consumer to decide what deletion
     * means to it, and they would decide differently: search would drop the document, the warehouse
     * would keep the dimension row, and the two would disagree about a product that still appears in
     * order history. Carrying the product's CURRENT STATE lets each consumer apply its own rule to
     * the same fact.
     *
     * <p>It is also what makes a read model rebuildable: replaying these events reconstructs the
     * index exactly, which a stream of deltas could not do.
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
