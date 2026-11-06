package com.ecommerce.inventory.saga;

import com.ecommerce.inventory.dto.InventoryDtos.ReserveLine;
import com.ecommerce.inventory.entity.InsufficientStockException;
import com.ecommerce.inventory.event.Events;
import com.ecommerce.inventory.outbox.OutboxWriter;
import com.ecommerce.inventory.repository.ProcessedEventRepository;
import com.ecommerce.inventory.service.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * inventory-service's half of the saga.
 *
 * <h2>Everything in one transaction, and that is the whole trick</h2>
 *
 * <p>Each handler does three things inside a single database transaction:
 *
 * <ol>
 *   <li>writes the idempotency guard ({@code processed_events});</li>
 *   <li>performs the effect (reserve / release / confirm);</li>
 *   <li>appends the outgoing event to the outbox.</li>
 * </ol>
 *
 * <p>Because they commit together, there is no arrangement of crashes that produces a half-state.
 * Reserve without a guard row (so a redelivery reserves twice)? Impossible — same transaction.
 * Reserve without announcing it (so the order waits forever)? Impossible — same transaction. Announce
 * a reservation that did not happen? Impossible.
 *
 * <p>That is what the outbox <em>buys</em>, and it is worth stating as the positive claim rather
 * than as "we avoid dual writes": <strong>the effect, the guard against repeating it, and the
 * announcement that it happened are one atomic fact.</strong>
 *
 * <h2>The event id comes from a header</h2>
 *
 * <p>Read before the payload is interpreted, so the guard runs regardless of what the body turns out
 * to contain. It is the producer's outbox row id, which is stable across redeliveries of the same
 * event and different for genuinely different ones — unlike a Kafka offset (changes on republish) or
 * a per-message UUID (changes when a producer restarts).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InventorySagaListener {

    private final InventoryService inventory;
    private final OutboxWriter outbox;
    private final ProcessedEventRepository processed;

    @Value("${app.kafka.topics.inventory-reserved}")
    private String reservedTopic;

    @Value("${app.kafka.topics.inventory-failed}")
    private String failedTopic;

    /**
     * An order was placed: try to hold the stock.
     *
     * <p>Both outcomes emit an event. A failure that emitted nothing would leave the order PENDING
     * with no way to find out why — the recovery job would eventually time it out, two minutes
     * later, with a useless reason. <strong>A saga step that can fail must say so.</strong>
     */
    @KafkaListener(topics = "${app.kafka.topics.order-placed}",
            groupId = "${app.kafka.groups.inventory}")
    @Transactional
    public void onOrderPlaced(Events.OrderPlaced event,
                              @Header(name = "event-id", required = false) byte[] eventIdHeader) {

        String eventId = eventIdOf(eventIdHeader, "OrderPlaced", event.orderKey());
        if (!inventory.firstTimeSeeing(eventId, "OrderPlaced")) {
            log.info("OrderPlaced {} already handled; skipping", eventId);
            return;
        }

        List<ReserveLine> lines = event.lines().stream()
                .map(l -> new ReserveLine(l.productKey(), l.quantity()))
                .toList();

        try {
            int reserved = inventory.reserve(event.orderKey(), lines).size();
            outbox.append("Order", event.orderKey(), "InventoryReserved", reservedTopic,
                    new Events.InventoryReserved(event.orderKey(), event.totalAmount(),
                            reserved, Instant.now()));
            log.info("Saga: reserved {} line(s) for order {}", reserved, event.orderKey());

        } catch (InsufficientStockException out) {
            // A BUSINESS OUTCOME, not a failure. It must NOT propagate: a thrown exception here
            // would be retried three times and then dead-lettered, and the order would sit PENDING
            // until the recovery job gave up on it. Catching it and publishing InventoryFailed is
            // what turns "no stock" into a fast, explained cancellation.
            outbox.append("Order", event.orderKey(), "InventoryFailed", failedTopic,
                    new Events.InventoryFailed(event.orderKey(), out.getProductKey(),
                            out.getMessage(), Instant.now()));
            log.info("Saga: order {} cannot be reserved - {}", event.orderKey(), out.getMessage());
        }
    }

    /**
     * Payment succeeded: the reservation becomes a real removal.
     *
     * <p>Note which event this listens to. Waiting for {@code OrderConfirmed} from order-service
     * would work and would add a hop — inventory would be waiting for order-service to react to the
     * same fact inventory already knows. Consuming {@code PaymentCompleted} directly is what makes
     * this choreography rather than an orchestration with extra steps.
     */
    @KafkaListener(topics = "${app.kafka.topics.payment-completed}",
            groupId = "${app.kafka.groups.inventory}")
    @Transactional
    public void onPaymentCompleted(Events.PaymentCompleted event,
                                   @Header(name = "event-id", required = false) byte[] eventIdHeader) {

        String eventId = eventIdOf(eventIdHeader, "PaymentCompleted", event.orderKey());
        if (!inventory.firstTimeSeeing(eventId, "PaymentCompleted")) {
            return;
        }
        int confirmed = inventory.confirm(event.orderKey());
        log.info("Saga: confirmed {} reservation(s) for paid order {}", confirmed, event.orderKey());
    }

    /**
     * COMPENSATION. The order was cancelled, so the stock goes back on the shelf.
     *
     * <p>Listens to {@code OrderCancelled} rather than to {@code PaymentFailed}, and the difference
     * matters: order-service decides whether a cancellation actually requires a release (it does not
     * when inventory itself was the thing that failed). Listening to PaymentFailed directly would
     * make inventory re-derive that decision, and two services deriving the same rule is two places
     * for it to drift.
     */
    @KafkaListener(topics = "${app.kafka.topics.order-cancelled}",
            groupId = "${app.kafka.groups.inventory}")
    @Transactional
    public void onOrderCancelled(Events.OrderCancelled event,
                                 @Header(name = "event-id", required = false) byte[] eventIdHeader) {

        String eventId = eventIdOf(eventIdHeader, "OrderCancelled", event.orderKey());
        if (!inventory.firstTimeSeeing(eventId, "OrderCancelled")) {
            return;
        }
        int released = inventory.release(event.orderKey());
        log.info("Saga: released {} reservation(s) for cancelled order {} ({})",
                released, event.orderKey(), event.reason());
    }

    /**
     * The header, or a fallback derived from the event itself.
     *
     * <p>The fallback exists for events published by something other than the outbox relay — a test
     * harness, a manual {@code kafka-console-producer}, a future service that has not adopted the
     * pattern. It is deliberately derived from (type, aggregate) rather than random: a random
     * fallback would deduplicate nothing, which is worse than no fallback because it looks like it
     * works.
     */
    private static String eventIdOf(byte[] header, String type, String aggregateId) {
        if (header != null && header.length > 0) {
            return new String(header, java.nio.charset.StandardCharsets.UTF_8);
        }
        return type + ":" + aggregateId;
    }
}
