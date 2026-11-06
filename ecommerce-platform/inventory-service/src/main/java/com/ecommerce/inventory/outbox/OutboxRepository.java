package com.ecommerce.inventory.outbox;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Claim the next batch of unpublished events.
     *
     * <h2>{@code FOR UPDATE SKIP LOCKED}, and why both halves matter</h2>
     *
     * <p>{@code FOR UPDATE} takes a row lock, so two relay instances cannot both publish the same
     * event. On its own that is not enough: the second instance would <em>block</em> on the first
     * one's rows and then process exactly the same batch after it committed — two relays doing one
     * relay's work, serially, with extra latency.
     *
     * <p>{@code SKIP LOCKED} is what makes it a work queue. Rows another transaction has already
     * claimed are simply not returned, so the second instance takes the <em>next</em> hundred and
     * both make progress. It is the same primitive behind every "pull jobs from a table" pattern,
     * and it is the reason this relay scales horizontally without a leader election, a distributed
     * lock, or a scheduler that must run on exactly one node.
     *
     * <p>The Hibernate hint is required: without {@code jakarta.persistence.lock.timeout = -2}
     * (SKIP_LOCKED), {@code @Lock(PESSIMISTIC_WRITE)} emits a plain {@code FOR UPDATE} and the two
     * relays serialise. The failure is invisible — everything still works, just half as fast, under
     * concurrency nobody tests.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("SELECT e FROM OutboxEvent e WHERE e.publishedAt IS NULL ORDER BY e.createdAt ASC")
    List<OutboxEvent> claimUnpublished(Pageable pageable);

    long countByPublishedAtIsNull();

    /**
     * Events that have been sitting unpublished for too long.
     *
     * <p>The relay retries forever, which is correct, and also means a permanently-failing event is
     * silent. This is what a monitor asks so the silence becomes an alert.
     */
    @Query("SELECT e FROM OutboxEvent e WHERE e.publishedAt IS NULL AND e.createdAt < :before")
    List<OutboxEvent> findStale(@Param("before") Instant before);

    List<OutboxEvent> findByAggregateIdOrderByCreatedAt(String aggregateId);
}
