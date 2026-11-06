package com.ecommerce.inventory.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Publishes the outbox's depth as a metric.
 *
 * <h2>Why this is the one number to alert on</h2>
 *
 * <p>Everything else about this architecture degrades visibly. If the outbox relay stops, nothing
 * does: HTTP stays fast, the database stays healthy, orders keep being accepted and committed, and
 * every dashboard stays green - while no event reaches Kafka and every saga silently stops
 * progressing. The only observable symptom is that this number climbs and never comes back down.
 *
 * <p>Steady state is 0 or a handful. A relay poll drains up to {@code app.outbox.batch-size} rows
 * every {@code app.outbox.poll-interval-ms}, so a sustained non-zero value means events are arriving
 * faster than they are published, and a value that only ever rises means they are not being
 * published at all.
 *
 * <h2>Why a Gauge and not a counter</h2>
 *
 * <p>A counter answers "how many were published", which is a throughput question and is already
 * covered by the relay's own counters. This is a level, not a flow: the question is how many are
 * waiting <em>right now</em>, and the answer has to be able to go down.
 *
 * <p>The lambda runs on scrape, once every ten seconds, and is one indexed count against a partial
 * index ({@code idx_outbox_unpublished ... WHERE published_at IS NULL}). Cheap enough not to need
 * caching - and caching it would let the metric lag the thing it exists to detect.
 */
@Component
@RequiredArgsConstructor
public class OutboxMetrics implements InitializingBean {

    private final OutboxRepository outbox;
    private final MeterRegistry registry;

    @Override
    public void afterPropertiesSet() {
        Gauge.builder("outbox.pending", outbox, OutboxRepository::countByPublishedAtIsNull)
                .description("Outbox events written but not yet published to Kafka")
                .register(registry);
    }
}
