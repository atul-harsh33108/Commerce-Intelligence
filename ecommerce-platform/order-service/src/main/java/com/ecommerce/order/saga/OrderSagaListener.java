package com.ecommerce.order.saga;

import com.ecommerce.order.event.Events;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Where Kafka records become saga transitions.
 *
 * <p>Deliberately thin: it does no business reasoning at all. The listener's only jobs are to say
 * which topic maps to which handler and to let the message converter turn bytes into the right
 * record type. Every decision lives in {@link OrderSaga}, which is a plain {@code @Service} that can
 * be unit-tested and called from the recovery job without a broker in sight.
 *
 * <p>That separation is what makes the saga testable. A listener with logic in it can only be
 * exercised by publishing to a topic.
 *
 * <h2>The type comes from the method signature</h2>
 *
 * <p>The consumer is configured with a {@code StringDeserializer} and a
 * {@code StringJsonMessageConverter}, so the JSON is bound to whatever each method declares it
 * wants. That is what lets one service consume four topics carrying four different event types
 * without a type header — and without the class-name coupling a type header creates (D2).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderSagaListener {

    private final OrderSaga saga;

    @KafkaListener(
            topics = "${app.kafka.topics.inventory-reserved}",
            groupId = "${app.kafka.groups.order}")
    public void onInventoryReserved(Events.InventoryReserved event) {
        saga.onInventoryReserved(event);
    }

    @KafkaListener(
            topics = "${app.kafka.topics.inventory-failed}",
            groupId = "${app.kafka.groups.order}")
    public void onInventoryFailed(Events.InventoryFailed event) {
        saga.onInventoryFailed(event);
    }

    @KafkaListener(
            topics = "${app.kafka.topics.payment-completed}",
            groupId = "${app.kafka.groups.order}")
    public void onPaymentCompleted(Events.PaymentCompleted event) {
        saga.onPaymentCompleted(event);
    }

    @KafkaListener(
            topics = "${app.kafka.topics.payment-failed}",
            groupId = "${app.kafka.groups.order}")
    public void onPaymentFailed(Events.PaymentFailed event) {
        saga.onPaymentFailed(event);
    }
}
