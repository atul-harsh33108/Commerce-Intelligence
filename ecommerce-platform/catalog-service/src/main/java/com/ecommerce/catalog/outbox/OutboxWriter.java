package com.ecommerce.catalog.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Appends an event to the outbox, inside the caller's transaction.
 *
 * <p>{@code Propagation.MANDATORY} is the whole design, expressed as an annotation.
 *
 * <p>It means this method <strong>refuses to run without an existing transaction</strong>. If some
 * future caller writes an event outside one — from a scheduled job, from a Kafka listener with no
 * transaction manager, from a controller that forgot {@code @Transactional} — Spring throws at that
 * call site instead of quietly opening a new transaction and committing the event separately from
 * the business change. Which would be the dual-write bug again, wearing the costume of its own
 * solution.
 *
 * <p>{@code REQUIRED} (the default) would have been the "safe" choice and is exactly wrong here: it
 * would make the failure silent.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxWriter {

    private final OutboxRepository outbox;
    private final ObjectMapper objectMapper;
    private final Tracer tracer;

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(String aggregateType, String aggregateId, String eventType,
                       String topic, Object payload) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            // Serialised HERE, inside the transaction, on purpose. An event that cannot be
            // serialised must fail the business transaction rather than be discovered by the relay
            // an hour later, when the order it describes is already durable and unannounceable.
            throw new IllegalStateException(
                    "Event " + eventType + " for " + aggregateId + " is not serialisable", ex);
        }

        OutboxEvent event = OutboxEvent.builder()
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .eventType(eventType)
                .topic(topic)
                .payload(json)
                .traceparent(currentTraceparent())
                .build();

        outbox.save(event);
        log.debug("Outbox <- {} for {} ({})", eventType, aggregateId, event.getEventId());
        return event.getEventId();
    }

    /**
     * The current trace as a W3C {@code traceparent}, or null if this thread is not being traced.
     *
     * <p>Formatted by hand rather than by asking the tracer to inject into a carrier, because the
     * carrier here is a database column rather than a set of headers, and the four-field format is
     * fixed by the specification: {@code version-traceid-spanid-flags}.
     *
     * <p>The flags byte is the part worth not getting wrong. {@code 01} means "sampled"; passing
     * {@code 00} for an unsampled span would be honest and would also mean the downstream services
     * drop every span for this event, so a saga sampled *in* at the edge would go dark the moment it
     * crossed the outbox. The sampling decision is inherited from the span that is actually here.
     */
    private String currentTraceparent() {
        Span span = tracer.currentSpan();
        if (span == null) {
            return null;
        }
        var ctx = span.context();
        return "00-" + ctx.traceId() + "-" + ctx.spanId() + "-"
                + (Boolean.FALSE.equals(ctx.sampled()) ? "00" : "01");
    }
}
