package com.ecommerce.catalog.outbox;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The timer. Runs forever, never throws, and does no work of its own.
 *
 * <h2>Why polling, when Debezium is already in this stack (Step 8)</h2>
 *
 * <p>Debezium's Outbox Event Router tails the write-ahead log and is the better production answer:
 * no polling load, millisecond latency. It is deliberately not used here because <strong>the outbox
 * has to work before Debezium exists</strong>. Step 5 is where the ordering guarantee is introduced
 * and proven with a fault-injection test; Step 8 is three steps later and stands up an entire Kafka
 * Connect cluster. Making the saga untestable without Connect would be the wrong dependency
 * direction. Recorded as D9, with the trade-off tabulated.
 *
 * <p>The work itself is in {@link OutboxPublisher}, in a separate bean, for the reason documented
 * there — {@code @Transactional} does not apply to a call a bean makes to itself.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxRelay {

    private final OutboxPublisher publisher;
    private final OutboxRepository outbox;

    /**
     * Off in tests that do not want a broker.
     *
     * <p>A scheduled bean failing every 500 ms because Kafka is absent turns a test log into noise.
     * The flag is checked here rather than by excluding the bean, so a test can still drive
     * {@link OutboxPublisher#publishBatch()} directly and observe exactly one pass.
     */
    @Value("${app.outbox.enabled:true}")
    private boolean enabled;

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:500}")
    public void scheduledPass() {
        if (!enabled) {
            return;
        }
        try {
            publisher.publishBatch();
        } catch (RuntimeException ex) {
            // The relay must survive a broker outage, which is the failure mode it exists for. An
            // uncaught exception from a @Scheduled method is logged by the executor and - depending
            // on the executor - can stop the schedule entirely.
            log.error("Outbox relay pass failed: {}", ex.toString());
        }
    }

    /**
     * Events that have been unpublished for longer than they should be.
     *
     * <p>The relay retries forever, which is correct and also means a permanently-failing event is
     * <em>silent</em>. This turns that silence into a log line somebody can alert on. It does not
     * attempt a fix: an event that has failed for five minutes needs a person, and a "self-healing"
     * step here would only hide it for longer.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void warnAboutStuckEvents() {
        if (!enabled) {
            return;
        }
        List<OutboxEvent> stale = outbox.findStale(Instant.now().minus(Duration.ofMinutes(5)));
        if (!stale.isEmpty()) {
            log.error("{} outbox event(s) have been unpublished for over 5 minutes. "
                            + "Oldest: {} ({}), {} attempts, last error: {}",
                    stale.size(), stale.get(0).getEventId(), stale.get(0).getEventType(),
                    stale.get(0).getAttempts(), stale.get(0).getLastError());
        }
    }
}
