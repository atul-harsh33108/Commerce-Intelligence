package com.ecommerce.inventory.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * "I have already handled this event."
 *
 * <p>Kafka delivers at least once. Not because it is unreliable — because the alternative is worse:
 * committing the offset <em>before</em> processing would remove duplicates and introduce silent loss
 * instead. A rebalance, a slow poll, a restarted pod and a producer retry all produce a redelivery,
 * so <strong>at-least-once is a statement about your code, not about the broker</strong>.
 *
 * <p>The row is written in the same transaction as the effect it guards, so the guard and the
 * reservation commit together or not at all. A guard held in memory would be erased by exactly the
 * event (a crash) that causes the redelivery it is supposed to catch.
 *
 * <p>The primary key is the event id the producer stamped on the outbox row, which is stable across
 * redeliveries of the same event and different for genuinely different ones. Deduplicating on a
 * per-<em>message</em> id would not work: a producer that republishes after a restart generates a
 * fresh one, and the same order would be reserved twice.
 */
@Entity
@Table(name = "processed_events")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProcessedEvent {

    @Id
    @Column(name = "event_id", length = 100)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 80)
    private String eventType;

    @Column(name = "processed_at", nullable = false)
    @Builder.Default
    private Instant processedAt = Instant.now();
}
