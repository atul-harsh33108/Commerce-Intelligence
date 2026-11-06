package com.ecommerce.payment.event;

import java.math.BigDecimal;
import java.time.Instant;

/** The saga's vocabulary, as payment-service understands it. Its own copy (D2). */
public final class Events {

    private Events() {}

    /**
     * Consumed - and note what it carries: the ORDER TOTAL.
     *
     * <p>Payment charges the amount the event tells it to, and does not call order-service to ask.
     * That is not laziness: the amount is part of the fact "this order was placed for R$ X", and
     * looking it up later would charge whatever the order says *now*, which is a different number
     * if anything has edited it in between. An event carries the state that was true when it
     * happened.
     */
    public record InventoryReserved(
            String orderKey, BigDecimal amount, int linesReserved, Instant occurredAt) {}

    /** Published. */
    public record PaymentCompleted(
            String orderKey, Long paymentId, BigDecimal amount, Instant occurredAt) {}

    /** Published. */
    public record PaymentFailed(String orderKey, String reason, Instant occurredAt) {}
}
