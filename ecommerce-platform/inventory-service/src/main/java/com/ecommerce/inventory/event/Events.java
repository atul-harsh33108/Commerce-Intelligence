package com.ecommerce.inventory.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The saga's vocabulary, as inventory-service understands it.
 *
 * <p>Its own copy, not a shared jar (D2). Note that {@code OrderPlaced} here declares FEWER fields
 * than order-service's version - no {@code customerKey}, no {@code totalAmount} - because inventory
 * does not need them. Jackson ignores unknown properties, so this is not a compatibility hazard: it
 * is the loose coupling the duplication buys. A shared record would have forced this service to
 * recompile every time an unrelated field was added to an order.
 */
public final class Events {

    private Events() {}

    /**
     * Consumed. Deliberately narrower than the producer's record - no customerKey, no unit prices.
     *
     * <p>{@code totalAmount} IS carried, and only because it has to be forwarded: payment-service
     * charges the amount {@code InventoryReserved} tells it to, and inventory is the service that
     * emits that event. Passing a value through a service that has no use for it is a smell, and the
     * alternative is worse - payment calling order-service to ask "how much?" would charge whatever
     * the order says NOW rather than what was true when it was placed, and would make payment
     * unable to work while order-service is down.
     *
     * <p>An event carries the state that was true when it happened. That is the rule that makes the
     * pass-through correct rather than lazy.
     */
    public record OrderPlaced(String orderKey, java.math.BigDecimal totalAmount,
                              List<Line> lines, Instant occurredAt) {
        public record Line(String productKey, int quantity) {}
    }

    /** Consumed. */
    public record PaymentCompleted(String orderKey, Long paymentId, Instant occurredAt) {}

    /** Consumed. */
    public record OrderCancelled(String orderKey, String reason, Instant occurredAt) {}

    /** Published. Carries the amount forward for payment-service - see OrderPlaced above. */
    public record InventoryReserved(String orderKey, java.math.BigDecimal amount,
                                    int linesReserved, Instant occurredAt) {}

    /** Published. Carries the reason so the customer is told which line failed, not just "failed". */
    public record InventoryFailed(
            String orderKey, String productKey, String reason, Instant occurredAt) {}
}
