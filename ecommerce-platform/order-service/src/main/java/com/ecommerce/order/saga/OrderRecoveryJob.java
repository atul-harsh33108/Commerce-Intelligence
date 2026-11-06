package com.ecommerce.order.saga;

import com.ecommerce.order.entity.Order;
import com.ecommerce.order.entity.OrderStatus;
import com.ecommerce.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The saga's last line of defence.
 *
 * <h2>What it is for, and what it is not for</h2>
 *
 * <p>The outbox guarantees that an event is <em>published</em>. It guarantees nothing about a
 * consumer that dies before handling it, a service that is down for an hour, or a message that ends
 * up in a dead-letter topic. An order can therefore sit in PENDING or AWAITING_PAYMENT forever, with
 * stock reserved against it, and no error anywhere — because from every individual service's point
 * of view nothing failed.
 *
 * <p>This job is the answer: <strong>any saga that has been in flight longer than it could
 * legitimately take is assumed dead and compensated.</strong> It is not a retry. It does not try to
 * push the saga forward, because it cannot know how far it got, and a second attempt at a step that
 * actually succeeded is how a customer gets charged twice.
 *
 * <h2>The window it cannot close</h2>
 *
 * <p>There is a genuine race, and it is worth naming rather than pretending away: this job may
 * compensate an order at the moment its payment is being captured. The result is a CANCELED order
 * with a real charge against it — which {@code OrderSaga.onPaymentCompleted} detects and logs as
 * "a refund is owed", loudly, rather than silently confirming or silently dropping.
 *
 * <p>The timeout is what makes that rare: it has to be longer than any legitimate saga, so it is
 * minutes rather than seconds. Making it fully safe needs the compensation and the payment to agree
 * on a decision — a status the payment service checks before capturing — which is a distributed
 * consensus problem and a much larger design. Recording the exposure is more useful than a mechanism
 * that looks like it closes it and does not.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderRecoveryJob {

    private static final List<OrderStatus> IN_FLIGHT =
            List.of(OrderStatus.PENDING, OrderStatus.AWAITING_PAYMENT);

    private final OrderRepository orders;
    private final OrderSaga saga;

    @Value("${app.saga.timeout:PT2M}")
    private Duration timeout;

    @Value("${app.saga.recovery-enabled:true}")
    private boolean enabled;

    /**
     * {@code fixedDelay}, not {@code fixedRate}.
     *
     * <p>{@code fixedRate} schedules the next run a fixed interval after the previous one
     * <em>started</em>, so a pass that takes longer than the interval overlaps with itself — two
     * threads compensating the same orders. {@code fixedDelay} measures from completion. On a job
     * that writes, that difference is a correctness property rather than a preference.
     */
    @Scheduled(fixedDelayString = "${app.saga.recovery-interval-ms:30000}",
            initialDelayString = "${app.saga.recovery-interval-ms:30000}")
    public void compensateStuckSagas() {
        if (!enabled) {
            return;
        }

        Instant cutoff = Instant.now().minus(timeout);
        List<Order> stuck = orders.findStuck(IN_FLIGHT, cutoff);
        if (stuck.isEmpty()) {
            return;
        }

        log.warn("{} order(s) have been in flight for over {}; compensating", stuck.size(), timeout);
        for (Order order : stuck) {
            try {
                // Only orders that got as far as a reservation need one released. A PENDING order
                // never had its OrderPlaced consumed, so there is nothing to give back.
                boolean reserved = order.getStatus() == OrderStatus.AWAITING_PAYMENT;
                saga.cancel(order.getOrderKey(),
                        "Saga timed out after " + timeout + " in state " + order.getStatus(),
                        reserved);
            } catch (RuntimeException ex) {
                // One order failing must not stop the rest. Each cancel() is its own transaction.
                log.error("Could not compensate order {}: {}", order.getOrderKey(), ex.toString());
            }
        }
    }
}
