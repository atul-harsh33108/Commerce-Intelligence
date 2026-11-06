package com.ecommerce.order.saga;

import com.ecommerce.order.entity.Order;
import com.ecommerce.order.entity.OrderStatus;
import com.ecommerce.order.event.Events;
import com.ecommerce.order.outbox.OutboxWriter;
import com.ecommerce.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * The order's half of the saga: how it reacts to what the other services say.
 *
 * <h2>Choreography, not orchestration, and the reason is honest</h2>
 *
 * <p>There is no central saga coordinator. Each service listens for the events that concern it and
 * emits the next one. The alternative — an orchestrator that tells inventory to reserve, waits, then
 * tells payment to charge — is easier to read and easier to debug, and it is what a larger system
 * should probably use.
 *
 * <p>Choreography is used here because the flow is short (three steps) and because the orchestrator
 * version has a failure mode this one does not: <strong>the orchestrator is a single point of
 * failure that holds the saga's state in flight.</strong> With choreography, every transition is a
 * durable event and a durable status change; kill any service mid-saga and the surviving state is
 * enough to finish or compensate. The cost, stated plainly, is that "what is the flow?" cannot be
 * read from one file — it is distributed across three listeners, which is exactly the criticism
 * levelled at choreographed sagas and it is correct.
 *
 * <h2>The full flow</h2>
 *
 * <pre>
 *   order    place()          -> orders row PENDING + outbox OrderPlaced   [ONE transaction]
 *   relay                     -> ecommerce.order.placed
 *   inventory  on OrderPlaced -> reserve  -> outbox InventoryReserved
 *                             -> or fail  -> outbox InventoryFailed
 *   order    on Reserved      -> AWAITING_PAYMENT
 *   payment  on Reserved      -> charge   -> outbox PaymentCompleted / PaymentFailed
 *   order    on Completed     -> CONFIRMED + outbox OrderConfirmed
 *   inventory on Completed    -> confirm reservations (stock leaves)
 *   order    on Failed        -> CANCELED  + outbox OrderCancelled     [COMPENSATION]
 *   inventory on Cancelled    -> release reservations                  [COMPENSATION]
 * </pre>
 *
 * <h2>Every handler here is idempotent by state</h2>
 *
 * <p>Not by a guard table, unlike inventory-service's. The difference is deliberate: these handlers
 * only ever move an order <em>forward</em> through a state machine, so "already CONFIRMED" is a
 * sufficient answer to a redelivered {@code PaymentCompleted}. Inventory's effects are arithmetic on
 * a counter, where "already applied" is not visible in the result, and those need the guard.
 *
 * <p>The rule that generalises: <strong>a state transition can be idempotent by its own state; an
 * increment cannot.</strong>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderSaga {

    private final OrderRepository orders;
    private final OutboxWriter outbox;

    @Value("${app.kafka.topics.order-confirmed}")
    private String orderConfirmedTopic;

    @Value("${app.kafka.topics.order-cancelled}")
    private String orderCancelledTopic;

    /** Stock is held. Nothing has been charged yet. */
    @Transactional
    public void onInventoryReserved(Events.InventoryReserved event) {
        Optional<Order> found = orders.findByOrderKey(event.orderKey());
        if (found.isEmpty()) {
            // An event about an order this service does not have. Not an error to retry: it means
            // the event is from another environment, or the order was purged. Logging and moving on
            // beats stalling the partition on something no retry can fix.
            log.warn("InventoryReserved for unknown order {} - ignoring", event.orderKey());
            return;
        }
        Order order = found.get();

        if (order.getStatus() != OrderStatus.PENDING) {
            log.debug("Order {} is {}, not PENDING; ignoring InventoryReserved",
                    order.getOrderKey(), order.getStatus());
            return;
        }

        order.setStatus(OrderStatus.AWAITING_PAYMENT);
        order.setReservedAt(Instant.now());
        log.info("Saga: order {} -> AWAITING_PAYMENT ({} line(s) reserved)",
                order.getOrderKey(), event.linesReserved());
    }

    /** Stock could not be held. Nothing to compensate — nothing was taken. */
    @Transactional
    public void onInventoryFailed(Events.InventoryFailed event) {
        cancel(event.orderKey(), "Out of stock: " + event.reason(), false);
    }

    /** Paid. The reservation becomes a real removal, driven by inventory's own listener. */
    @Transactional
    public void onPaymentCompleted(Events.PaymentCompleted event) {
        Optional<Order> found = orders.findByOrderKey(event.orderKey());
        if (found.isEmpty()) {
            log.warn("PaymentCompleted for unknown order {} - ignoring", event.orderKey());
            return;
        }
        Order order = found.get();

        if (order.getStatus() == OrderStatus.CONFIRMED) {
            log.debug("Order {} already CONFIRMED; ignoring redelivered PaymentCompleted",
                    order.getOrderKey());
            return;
        }
        if (order.getStatus() == OrderStatus.CANCELED) {
            // A payment that completed for an order the saga already gave up on. This is the case
            // the recovery job creates: it compensated after a timeout, and the payment arrived
            // afterwards. It is a REAL divergence, and the honest thing is to shout rather than to
            // silently confirm or silently drop.
            log.error("PaymentCompleted for order {} which is already CANCELED. "
                            + "A refund is owed. payment_id={} amount={}",
                    order.getOrderKey(), event.paymentId(), event.amount());
            return;
        }

        order.setStatus(OrderStatus.CONFIRMED);
        order.setPaidAt(Instant.now());
        order.setApprovedAt(Instant.now());

        outbox.append("Order", order.getOrderKey(), "OrderConfirmed", orderConfirmedTopic,
                new Events.OrderConfirmed(order.getOrderKey(), order.getUserId(),
                        order.getTotalAmount(), Instant.now()));

        log.info("Saga: order {} -> CONFIRMED", order.getOrderKey());
    }

    /** Payment declined. Compensate: the reservation has to go back. */
    @Transactional
    public void onPaymentFailed(Events.PaymentFailed event) {
        cancel(event.orderKey(), "Payment failed: " + event.reason(), true);
    }

    /**
     * The compensating transition.
     *
     * <p>{@code needsRelease} decides whether an {@code OrderCancelled} event is emitted at all.
     * When inventory <em>failed</em> to reserve there is nothing to put back, and publishing a
     * release instruction would be an instruction to undo something that never happened — harmless
     * here because inventory's release is idempotent by state, and still wrong to send: an event
     * that says something untrue is a bug waiting for a consumer that trusts it.
     */
    @Transactional
    public void cancel(String orderKey, String reason, boolean needsRelease) {
        Optional<Order> found = orders.findByOrderKey(orderKey);
        if (found.isEmpty()) {
            log.warn("Cancellation for unknown order {} - ignoring", orderKey);
            return;
        }
        Order order = found.get();

        if (order.getStatus() == OrderStatus.CANCELED) {
            return;   // idempotent by state
        }
        if (order.getStatus() == OrderStatus.CONFIRMED) {
            log.error("Refusing to cancel order {}, which is already CONFIRMED. Reason given: {}",
                    orderKey, reason);
            return;
        }

        order.setStatus(OrderStatus.CANCELED);
        order.setFailureReason(reason.length() > 255 ? reason.substring(0, 255) : reason);
        order.setCompensatedAt(Instant.now());

        if (needsRelease) {
            outbox.append("Order", orderKey, "OrderCancelled", orderCancelledTopic,
                    new Events.OrderCancelled(orderKey, order.getUserId(), reason, Instant.now()));
        }

        log.info("Saga: order {} -> CANCELED ({})", orderKey, reason);
    }
}
