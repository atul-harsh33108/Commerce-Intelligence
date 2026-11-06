package com.ecommerce.order.service;

import com.ecommerce.order.client.CatalogClient;
import com.ecommerce.order.client.CatalogGateway;
import com.ecommerce.order.client.InventoryGateway;
import com.ecommerce.order.event.Events;
import com.ecommerce.order.outbox.OutboxWriter;
import com.ecommerce.order.dto.OrderDtos.OrderResponse;
import com.ecommerce.order.dto.OrderDtos.PageResponse;
import com.ecommerce.order.dto.OrderDtos.PlaceOrderRequest;
import com.ecommerce.order.entity.Order;
import com.ecommerce.order.entity.OrderItem;
import com.ecommerce.order.entity.OrderStatus;
import com.ecommerce.order.exception.OrderNotAllowedException;
import com.ecommerce.order.exception.ResourceNotFoundException;
import com.ecommerce.order.repository.OrderRepository;
import com.ecommerce.order.security.AuthenticatedUser;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Placing an order.
 *
 * <h2>The shape of Step 3, and what Step 5 changes</h2>
 *
 * <p>Here the flow is <strong>synchronous</strong>: price the cart against the catalogue, write the
 * order, reserve the stock, and answer. Three services, two network hops, one HTTP request holding
 * the customer's connection open for all of it.
 *
 * <p>That is deliberately the wrong architecture, kept long enough to see why. The seams are
 * visible: every hop is a thing that can be slow, a thing that can fail after the previous one
 * succeeded, and a thing the customer waits for. Step 5 replaces the second hop with an event and a
 * saga, and the outbox is what makes "the order was written" and "the event was announced" a single
 * atomic fact rather than two hopeful ones.
 *
 * <h2>What is already right, and stays</h2>
 *
 * <ul>
 *   <li>The client sends product keys and quantities. <strong>The server prices them.</strong></li>
 *   <li>The price is <em>copied</em> onto the order line, not referenced. An order is a contract at
 *       a price; re-reading the catalogue later would rewrite history.</li>
 *   <li>The identity comes from the token, never from the request body.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository orders;
    private final CatalogGateway catalog;
    private final OutboxWriter outbox;
    private final Tracer tracer;

    /**
     * Still here, and only used by {@link #markPaid} and {@link #cancel}.
     *
     * <p>Those two are the Step 3 synchronous path, kept because a system that can only be driven by
     * publishing to a topic is a system that can only be debugged by publishing to a topic. The
     * production path from Step 5 is the saga, and it never calls this.
     */
    private final InventoryGateway inventory;

    @Value("${app.kafka.topics.order-placed}")
    private String orderPlacedTopic;

    /**
     * Freight, flat, because Olist's per-item freight is a fact about a shipment this platform does
     * not make. Stated as a constant rather than computed from a made-up formula: a fabricated
     * shipping calculator would look more sophisticated and be no more true.
     */
    private static final BigDecimal FLAT_FREIGHT = new BigDecimal("15.00");

    @Transactional
    public OrderResponse place(PlaceOrderRequest request, AuthenticatedUser caller) {
        List<String> keys = request.lines().stream()
                .map(PlaceOrderRequest.Line::productKey).distinct().toList();

        // 1. Price the cart. Throws CatalogUnavailableException -> 503 if the catalogue is down;
        //    it never proceeds with a zero price (see CatalogGateway).
        Map<String, CatalogClient.ProductSnapshot> priced = catalog.priceLookup(keys);

        List<String> unknown = keys.stream().filter(k -> !priced.containsKey(k)).toList();
        if (!unknown.isEmpty()) {
            throw new ResourceNotFoundException("Unknown product key(s): " + unknown);
        }
        List<String> inactive = priced.values().stream()
                .filter(p -> !p.active() || p.price() == null)
                .map(CatalogClient.ProductSnapshot::productKey).toList();
        if (!inactive.isEmpty()) {
            throw new ResourceNotFoundException("Product(s) not currently for sale: " + inactive);
        }

        // 2. Write the order. PENDING: durable, but nothing downstream knows about it yet.
        Order order = Order.builder()
                .orderKey(UUID.randomUUID().toString())
                .customerKey(caller.username())
                .userId(caller.id())
                .status(OrderStatus.PENDING)
                .purchasedAt(Instant.now())
                .build();

        int lineNo = 1;
        for (PlaceOrderRequest.Line line : request.lines()) {
            CatalogClient.ProductSnapshot product = priced.get(line.productKey());
            order.addItem(OrderItem.builder()
                    .lineNo(lineNo++)
                    .productKey(line.productKey())
                    .quantity(line.quantity())
                    .unitPrice(product.price())
                    .freightAmount(FLAT_FREIGHT)
                    .build());
        }
        order.recalculateTotal();
        orders.save(order);

        // 3. Announce it. THE OUTBOX (Step 5).
        //
        //    This replaced a synchronous `inventory.reserve(...)` call made inside this same
        //    transaction. Two things were wrong with that, and the second is the serious one:
        //
        //      a network call inside a database transaction holds a connection open for the
        //      duration of somebody else's latency - so inventory being slow consumed order-service's
        //      connection pool;
        //
        //      and there was no way to make "the order is durable" and "somebody was told about it"
        //      one atomic fact. Reserving before the commit reserves stock for orders that roll
        //      back; announcing after it loses the announcement when the process dies in between.
        //
        //    The outbox row is written HERE, in this transaction, alongside the order. Either both
        //    are durable or neither is. A separate relay then publishes it - and if the relay, the
        //    broker, or this process dies at any point, the unpublished row is still sitting in the
        //    database waiting to be sent.
        //
        //    What the customer gets in exchange: the response returns as soon as the order is
        //    durable, without waiting for inventory at all. PENDING is an honest status - it says
        //    "we have your order, we are working on it" rather than pretending the whole saga
        //    finished inside one HTTP request.
        // Put the order key on the trace (Step 13).
        //
        // Without this the trace is findable only by time - "the order somebody placed around
        // 14:32" - which is exactly the wrong index for the question people actually ask, which is
        // "what happened to order a96783e8". The key is generated here, server-side, so it appears
        // in no request URL and no header; nothing else can put it on the span.
        //
        // One tag on the SERVER span propagates nowhere by itself, and does not need to: Zipkin
        // indexes tags per span and returns the whole trace for a hit on any of them.
        Span current = tracer.currentSpan();
        if (current != null) {
            current.tag("order.key", order.getOrderKey());
        }

        outbox.append("Order", order.getOrderKey(), "OrderPlaced", orderPlacedTopic,
                new Events.OrderPlaced(
                        order.getOrderKey(),
                        order.getUserId(),
                        order.getCustomerKey(),
                        order.getTotalAmount(),
                        order.getItems().stream()
                                .map(i -> new Events.OrderPlaced.Line(
                                        i.getProductKey(), i.getQuantity(), i.getUnitPrice()))
                                .toList(),
                        Instant.now()));

        log.info("Order {} placed by {} ({} line(s), total {}) - PENDING, OrderPlaced queued",
                order.getOrderKey(), caller.username(), order.getItems().size(),
                order.getTotalAmount());

        return OrderResponse.withItems(order);
    }

    /**
     * Marks an order paid and turns its reservations into real stock removals.
     *
     * <p>The Step 3 synchronous path, kept for tests and for driving the system without a broker.
     * The production transition since Step 5 is {@code OrderSaga.onPaymentCompleted}, which reaches
     * the same state by consuming an event. Two paths to one state is a real duplication risk, and
     * the mitigation is that both go through the same status guard and the same idempotency rule -
     * "already CONFIRMED" is a no-op in each.
     */
    @Transactional
    public OrderResponse markPaid(String orderKey) {
        Order order = orders.findByOrderKeyWithItems(orderKey)
                .orElseThrow(() -> new ResourceNotFoundException("No order " + orderKey));

        if (order.getStatus() == OrderStatus.CONFIRMED) {
            // Idempotent: a redelivered PaymentCompleted, or a retried HTTP call, must not confirm
            // stock twice.
            return OrderResponse.withItems(order);
        }

        inventory.confirm(orderKey);
        order.setStatus(OrderStatus.CONFIRMED);
        order.setApprovedAt(Instant.now());
        log.info("Order {} confirmed", orderKey);
        return OrderResponse.withItems(order);
    }

    /**
     * Compensation: release the stock and record why.
     *
     * <p>Idempotent by state. Cancelling an already-cancelled order is a no-op rather than a second
     * release, which matters because this is the path that runs on failures, and failures retry.
     */
    @Transactional
    public OrderResponse cancel(String orderKey, String reason) {
        Order order = orders.findByOrderKeyWithItems(orderKey)
                .orElseThrow(() -> new ResourceNotFoundException("No order " + orderKey));

        if (order.getStatus() == OrderStatus.CANCELED) {
            return OrderResponse.withItems(order);
        }
        if (order.getStatus() == OrderStatus.CONFIRMED) {
            // Refunding a confirmed order is a different business process with different accounting.
            // Refusing here is more honest than silently doing something cheaper.
            throw new OrderNotAllowedException(
                    "Order " + orderKey + " is already confirmed; cancellation requires a refund.");
        }

        inventory.release(orderKey);
        order.setStatus(OrderStatus.CANCELED);
        order.setFailureReason(reason);
        log.info("Order {} canceled: {}", orderKey, reason);
        return OrderResponse.withItems(order);
    }

    @Transactional(readOnly = true)
    public OrderResponse byKey(String orderKey, AuthenticatedUser caller) {
        Order order = orders.findByOrderKeyWithItems(orderKey)
                .orElseThrow(() -> new ResourceNotFoundException("No order " + orderKey));
        assertVisibleTo(order, caller);
        return OrderResponse.withItems(order);
    }

    @Transactional(readOnly = true)
    public OrderResponse byId(Long id, AuthenticatedUser caller) {
        Order order = orders.findByIdWithItems(id)
                .orElseThrow(() -> new ResourceNotFoundException("No order with id " + id));
        assertVisibleTo(order, caller);
        return OrderResponse.withItems(order);
    }

    /**
     * "My orders", scoped by the token.
     *
     * <p>The user id comes from the verified claim, so there is no id in the URL for a caller to
     * change. That is the whole IDOR class of bug removed by construction rather than by a check
     * somebody has to remember to write.
     */
    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> mine(AuthenticatedUser caller, Pageable pageable) {
        return PageResponse.of(orders
                .findByUserIdOrderByCreatedAtDesc(caller.id(), pageable)
                .map(OrderResponse::summary));
    }

    private void assertVisibleTo(Order order, AuthenticatedUser caller) {
        if ("ADMIN".equals(caller.role())) {
            return;
        }
        boolean mine = caller.id() != null && caller.id().equals(order.getUserId());
        if (!mine) {
            throw new OrderNotAllowedException("This order belongs to someone else.");
        }
    }
}
