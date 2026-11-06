package com.ecommerce.payment.saga;

import com.ecommerce.payment.dto.PaymentDtos.PaymentRequest;
import com.ecommerce.payment.dto.PaymentDtos.PaymentResponse;
import com.ecommerce.payment.entity.Payment;
import com.ecommerce.payment.event.Events;
import com.ecommerce.payment.outbox.OutboxWriter;
import com.ecommerce.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * payment-service's half of the saga: charge when stock is held, and say what happened.
 *
 * <h2>No separate idempotency guard, on purpose</h2>
 *
 * <p>Every other consumer on this platform writes a {@code processed_events} row. This one does not,
 * because it already has a stronger guarantee: the {@code payments.idempotency_key} unique
 * constraint, derived from {@code (orderKey, sequential)}. A redelivered {@code InventoryReserved}
 * produces the same key, finds the existing row, and returns the ORIGINAL result - so the customer
 * is charged once and the saga is told the same thing it was told the first time.
 *
 * <p>Adding a second guard would be strictly redundant and would create a new failure mode: a guard
 * row committed without a payment row, after which a genuine retry would be refused and the order
 * would strand. <strong>One idempotency mechanism per effect.</strong>
 *
 * <p>What it does mean is that a redelivery re-publishes {@code PaymentCompleted}. That is fine and
 * is the design: order-service's handler is idempotent by state ("already CONFIRMED, ignore"), and
 * inventory-service's has its own guard. At-least-once is handled where the effect is, not by trying
 * to make the wire exactly-once.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentSagaListener {

    private final PaymentService payments;
    private final OutboxWriter outbox;

    @Value("${app.kafka.topics.payment-completed}")
    private String completedTopic;

    @Value("${app.kafka.topics.payment-failed}")
    private String failedTopic;

    @KafkaListener(topics = "${app.kafka.topics.inventory-reserved}",
            groupId = "${app.kafka.groups.payment}")
    @Transactional
    public void onInventoryReserved(Events.InventoryReserved event) {
        PaymentResponse result = payments.pay(new PaymentRequest(
                event.orderKey(), 1, Payment.Method.CREDIT_CARD, 1, event.amount()));

        if ("CAPTURED".equals(result.status())) {
            outbox.append("Order", event.orderKey(), "PaymentCompleted", completedTopic,
                    new Events.PaymentCompleted(event.orderKey(), result.id(),
                            result.amount(), Instant.now()));
            log.info("Saga: captured {} for order {}", result.amount(), event.orderKey());
        } else {
            // DECLINED is a business outcome, so it is published rather than thrown. Throwing would
            // retry the charge three times against a gateway that already said no, then dead-letter
            // it - and the order would sit in AWAITING_PAYMENT until the recovery job timed it out.
            outbox.append("Order", event.orderKey(), "PaymentFailed", failedTopic,
                    new Events.PaymentFailed(event.orderKey(),
                            result.failureReason() == null ? "Declined" : result.failureReason(),
                            Instant.now()));
            log.info("Saga: payment declined for order {} - {}",
                    event.orderKey(), result.failureReason());
        }
    }
}
