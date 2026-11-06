package com.ecommerce.payment.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * One event, written in the same transaction as the business change it describes.
 *
 * <p>See V2__outbox.sql for why this table exists at all. The short version: a database write and a
 * Kafka send cannot be one atomic act, and pretending otherwise is the dual-write bug — the order
 * commits, the process dies, and nothing downstream ever hears about it.
 */
@Entity
@Table(name = "outbox_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxEvent {

    /**
     * Generated here, not by the database.
     *
     * <p>It has to exist before the insert because it is what consumers deduplicate on, and because
     * the relay needs it to be stable across republication — a database-generated id would be stable
     * too, but a UUID lets the producer put the id into the payload and headers without a round
     * trip.
     */
    @Id
    @Column(name = "event_id", nullable = false, updatable = false)
    @Builder.Default
    private UUID eventId = UUID.randomUUID();

    @Column(name = "aggregate_type", nullable = false, length = 40)
    private String aggregateType;

    /** Becomes the Kafka message key, which is what chooses the partition and therefore the order. */
    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 80)
    private String eventType;

    @Column(nullable = false, length = 120)
    private String topic;

    /**
     * The serialised event, as a JSON string.
     *
     * <p>Mapped with {@code columnDefinition = "jsonb"} rather than a custom Hibernate type: the
     * relay never parses it, so a String is exactly the right Java type, and Postgres still gets a
     * real jsonb column that can be queried when somebody needs to know what was published.
     *
     * <p>Serialising at write time rather than storing the object is deliberate. The payload is a
     * snapshot of what was true when the transaction committed; holding a reference to a live entity
     * and serialising later would publish whatever the object looks like at relay time, which is a
     * different fact.
     */
    @Column(nullable = false, columnDefinition = "jsonb")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    private String payload;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    /** Null until the relay has confirmed the broker accepted it. */
    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(nullable = false)
    @Builder.Default
    private Integer attempts = 0;

    @Column(name = "last_error", length = 500)
    private String lastError;

    /**
     * The W3C traceparent in force when this event was appended, or null if there was none.
     *
     * <p>It is stored because the relay publishes on a different thread, in a different transaction,
     * up to a poll interval later — so by the time the send happens, the trace context that caused
     * the event no longer exists anywhere in the process. Persisting it is the only way the
     * consumer's spans can be attached to the request that produced the event rather than to the
     * relay's own scheduled tick.
     */
    @Column(name = "traceparent", length = 64)
    private String traceparent;
}
