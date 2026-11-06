package com.ecommerce.order.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The saga's vocabulary, as order-service understands it.
 *
 * <p><strong>Every service has its own copy of these records</strong> and they are deliberately not
 * shared (D2). The consequence is worth stating precisely, because it looks like sloppiness and is
 * the opposite: inventory-service's {@code OrderPlaced} may declare fewer fields than this one, and
 * that is fine — Jackson ignores unknown properties, so a producer adding a field is an additive
 * change rather than an outage in a consumer nobody rebuilt. A shared jar would make it a
 * coordinated release instead.
 *
 * <p>This is also why {@code spring.json.add.type.headers: false} is set platform-wide. With type
 * headers on, {@code JsonSerializer} writes {@code com.ecommerce.order.event.OrderPlaced} into the
 * record and the consumer's deserializer tries to instantiate a class that does not exist in its
 * classpath. <em>A producer publishes facts, not Java types.</em>
 *
 * <h2>Naming: past tense, always</h2>
 *
 * <p>{@code OrderPlaced}, not {@code PlaceOrder}. An event is a statement that something has already
 * happened and cannot be refused; a command is a request that can be. Getting the tense wrong is how
 * an event bus quietly becomes an RPC mechanism with worse ergonomics — a consumer starts treating
 * {@code PlaceOrder} as an instruction it may reject, and the producer starts waiting for an answer.
 */
public final class Events {

    private Events() {}

    // --- Published by this service ---------------------------------------------------------------

    /**
     * An order exists and is durable.
     *
     * <p>Carries everything a consumer needs to act without calling back — the line items with their
     * prices, not just an order id. A "notification event" that carries only an id forces every
     * consumer to make a synchronous call to the producer, which reintroduces the coupling the event
     * was meant to remove and makes the producer a dependency of everyone who listens.
     */
    public record OrderPlaced(
            String orderKey,
            Long userId,
            String customerKey,
            BigDecimal totalAmount,
            List<Line> lines,
            Instant occurredAt
    ) {
        public record Line(String productKey, int quantity, BigDecimal unitPrice) {}
    }

    /** The saga completed. Consumed by search/analytics/notification-shaped listeners. */
    public record OrderConfirmed(
            String orderKey, Long userId, BigDecimal totalAmount, Instant occurredAt) {}

    /**
     * The saga compensated.
     *
     * <p>The reason travels with it. A cancellation event without one leaves every consumer — and
     * every support engineer — asking the order service why, which is a synchronous call at exactly
     * the moment something is already wrong.
     */
    public record OrderCancelled(
            String orderKey, Long userId, String reason, Instant occurredAt) {}

    // --- Consumed by this service ----------------------------------------------------------------

    public record InventoryReserved(
            String orderKey, BigDecimal amount, int linesReserved, Instant occurredAt) {}

    public record InventoryFailed(
            String orderKey, String productKey, String reason, Instant occurredAt) {}

    public record PaymentCompleted(
            String orderKey, Long paymentId, BigDecimal amount, Instant occurredAt) {}

    public record PaymentFailed(
            String orderKey, String reason, Instant occurredAt) {}
}
