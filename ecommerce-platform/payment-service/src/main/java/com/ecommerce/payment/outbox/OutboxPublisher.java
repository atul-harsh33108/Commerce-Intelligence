package com.ecommerce.payment.outbox;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One transactional pass over the outbox: claim, send, mark.
 *
 * <h2>Why this is a separate bean from {@link OutboxRelay}</h2>
 *
 * <p>It was not, at first, and the bug is worth keeping written down because it is the single most
 * common Spring mistake and it fails <em>loudly but misleadingly</em>.
 *
 * <p>The relay's {@code @Scheduled} method called its own {@code @Transactional} method:
 *
 * <pre>
 *   &#64;Scheduled void scheduledPass() { publishBatch(); }   // a plain Java call
 *   &#64;Transactional int publishBatch() { ... }
 * </pre>
 *
 * <p>{@code @Transactional} is implemented by a <strong>proxy</strong>. Calling a method on
 * {@code this} does not go through the proxy, so the annotation is simply not applied — and the
 * failure was 120 log lines of
 * {@code "Query requires transaction be in progress, but no transaction is known to be in progress"}
 * every 500 ms, from the pessimistic lock in {@code claimUnpublished}. Which points at the query,
 * not at the call.
 *
 * <p>The fix is to cross a bean boundary, so the proxy is in the path. Not
 * {@code @Transactional} on the scheduled method itself, which would work and would introduce a
 * second problem: that method <em>catches</em> exceptions to keep the relay alive across a broker
 * outage, and catching an exception inside a transaction that Spring has already marked
 * rollback-only produces {@code UnexpectedRollbackException} at commit. Separating "the thing that
 * runs on a timer and never dies" from "the unit of work that either commits or does not" is the
 * arrangement that makes both properties true.
 *
 * <h2>The transaction boundary is one batch</h2>
 *
 * <p>Claim, send and mark all commit together. A crash anywhere in the middle rolls back the marks,
 * so the events are re-sent next pass — duplicates, which consumers already deduplicate. The
 * alternative (commit each mark separately) narrows the duplicate window and gives up the row locks
 * one at a time, which is more code for a property nobody needs.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisher {

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final Tracer tracer;

    @Value("${app.outbox.batch-size:100}")
    private int batchSize;

    private final AtomicLong published = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    @Transactional
    public int publishBatch() {
        List<OutboxEvent> batch = outbox.claimUnpublished(PageRequest.of(0, batchSize));
        if (batch.isEmpty()) {
            return 0;
        }

        int sent = 0;
        for (OutboxEvent event : batch) {
            try {
                ProducerRecord<String, String> record = new ProducerRecord<>(
                        event.getTopic(), event.getAggregateId(), event.getPayload());

                // THE HEADER CONSUMERS DEDUPLICATE ON.
                //
                // The event id travels beside the payload rather than only inside it, so a consumer
                // can ask "have I seen this?" before it parses the body - which matters because the
                // guard has to run before the handler decides what type it is dealing with.
                record.headers().add(new RecordHeader("event-id",
                        event.getEventId().toString().getBytes(StandardCharsets.UTF_8)));
                record.headers().add(new RecordHeader("event-type",
                        event.getEventType().getBytes(StandardCharsets.UTF_8)));

                // BLOCKING, deliberately. A fire-and-forget send would let the mark below claim a
                // success the broker never granted - the same lost event the outbox exists to
                // prevent, reintroduced one layer down. With `acks: all` set platform-wide,
                // "acknowledged" means every in-sync replica has it.
                sendUnderOriginalTrace(event, record);

                // MARK AFTER SEND, never before. A crash between the send and the mark republishes
                // the event (a duplicate, which consumers handle); a crash between the mark and the
                // send would lose it. Given the choice, always duplicate.
                event.setPublishedAt(Instant.now());
                event.setLastError(null);
                sent++;
                published.incrementAndGet();

            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                recordFailure(event, ex);
                break;
            } catch (Exception ex) {
                recordFailure(event, ex);
                // Stop the batch rather than trying the rest. If the broker is unreachable the next
                // 99 fail identically - and, more importantly, continuing would publish later events
                // for the same aggregate before an earlier one, breaking the per-key ordering the
                // message key exists to provide.
                break;
            }
        }

        if (sent > 0) {
            log.debug("Outbox published {} event(s)", sent);
        }
        return sent;
    }

    private void recordFailure(OutboxEvent event, Exception ex) {
        event.setAttempts(event.getAttempts() + 1);
        String message = ex.getClass().getSimpleName() + ": " + ex.getMessage();
        event.setLastError(message.length() > 500 ? message.substring(0, 500) : message);
        failures.incrementAndGet();
        log.warn("Outbox event {} ({}) failed to publish, attempt {}: {}",
                event.getEventId(), event.getEventType(), event.getAttempts(), message);
    }

    public long publishedCount() {
        return published.get();
    }

    public long failureCount() {
        return failures.get();
    }

    /**
     * Sends the record inside a span parented to the trace that appended the event.
     *
     * <p><strong>Why not just copy the traceparent header onto the record.</strong> That was the
     * first version and it produces two {@code traceparent} headers, because Spring Kafka's own
     * observation injects one during {@code send()} — and which of the two a consumer reads is
     * decided by whichever library it happens to use. Restoring the context instead means there is
     * one code path doing the injecting, the same one that does it everywhere else on this platform.
     *
     * <p>It also produces something the header copy does not: a visible {@code outbox publish} span
     * sitting between the request and the consumer, whose duration is exactly the outbox delay. That
     * gap is the cost of the pattern, and this makes it a number on a screen rather than an
     * argument.
     *
     * <p>An event with no stored traceparent is sent as it always was. That is not a failure — it is
     * an event appended outside any request, such as the catalogue reindex.
     */
    private void sendUnderOriginalTrace(OutboxEvent event, ProducerRecord<String, String> record)
            throws Exception {
        TraceContext parent = parseTraceparent(event.getTraceparent());
        if (parent == null) {
            kafka.send(record).get(10, TimeUnit.SECONDS);
            return;
        }

        Span span = tracer.spanBuilder()
                .setParent(parent)
                .name("outbox publish")
                .tag("outbox.event_type", event.getEventType())
                .tag("outbox.topic", event.getTopic())
                .tag("messaging.destination.name", event.getTopic())
                .start();
        try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
            kafka.send(record).get(10, TimeUnit.SECONDS);
        } finally {
            span.end();
        }
    }

    /** Parses {@code version-traceid-spanid-flags}, returning null for anything that is not that. */
    private TraceContext parseTraceparent(String traceparent) {
        if (traceparent == null) {
            return null;
        }
        String[] parts = traceparent.split("-");
        if (parts.length != 4) {
            // Not worth an exception. A malformed value costs a disconnected trace; refusing to
            // publish the event would cost the order.
            log.warn("Ignoring malformed traceparent on outbox event: {}", traceparent);
            return null;
        }
        return tracer.traceContextBuilder()
                .traceId(parts[1])
                .spanId(parts[2])
                .sampled(!"00".equals(parts[3]))
                .build();
    }
}
