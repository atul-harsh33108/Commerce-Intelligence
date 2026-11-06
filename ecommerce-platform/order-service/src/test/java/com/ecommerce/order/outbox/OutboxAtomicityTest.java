package com.ecommerce.order.outbox;

import com.ecommerce.order.TestcontainersConfig;
import com.ecommerce.order.client.CatalogClient;
import com.ecommerce.order.client.CatalogGateway;
import com.ecommerce.order.dto.OrderDtos.PlaceOrderRequest;
import com.ecommerce.order.entity.OrderStatus;
import com.ecommerce.order.repository.OrderRepository;
import com.ecommerce.order.security.AuthenticatedUser;
import com.ecommerce.order.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The Definition of Done for Step 5, as executable statements.
 *
 * <p>The claim being tested is precise: <strong>a fault injected between "the order is committed"
 * and "the event is published" leaves no divergence.</strong> Not "we wrote an outbox class" — the
 * property, demonstrated by breaking the thing that would otherwise break.
 *
 * <p>Kafka is a mock rather than a Testcontainers broker, deliberately. The fault being injected is
 * "the send fails", and a mock is the only way to make it fail <em>on demand, deterministically, at
 * the exact instruction</em>. A real broker can be stopped, which tests something adjacent and
 * flakily. Postgres is real, because the atomicity claim is entirely about transaction boundaries
 * and an in-memory database would not exercise them the same way.
 */
@SpringBootTest(properties = {
        // No scheduled relay. Every pass in this class is driven explicitly, so "did it publish?"
        // is a question with one answer rather than a race with a background thread.
        "app.outbox.enabled=false",
        "app.saga.recovery-enabled=false"
})
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class OutboxAtomicityTest {

    @Autowired OrderRepository orders;
    @Autowired OutboxRepository outbox;
    @Autowired OutboxWriter writer;
    @Autowired OutboxPublisher publisher;
    @Autowired OrderService orderService;
    @Autowired TransactionTemplate transactions;

    /** Mocked so a send can be made to fail on the instruction we choose. */
    @MockitoBean KafkaTemplate<String, String> kafka;

    /** Mocked so the test needs no catalog-service. Pricing is not what is under test here. */
    @MockitoBean CatalogGateway catalog;

    private static final AuthenticatedUser CALLER = new AuthenticatedUser(7L, "tester", "USER");
    private static final String PRODUCT = "test-product-0001";

    @BeforeEach
    void setUp() {
        outbox.deleteAll();
        orders.deleteAll();

        when(catalog.priceLookup(any())).thenReturn(Map.of(PRODUCT,
                new CatalogClient.ProductSnapshot(PRODUCT, "a product", "toys",
                        new BigDecimal("10.00"), true)));

        // Default: the broker accepts everything.
        when(kafka.send(any(org.apache.kafka.clients.producer.ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
    }

    @SuppressWarnings("unchecked")
    private static <T> T mock(Class<T> type) {
        return org.mockito.Mockito.mock(type);
    }

    private PlaceOrderRequest oneLine() {
        return new PlaceOrderRequest(List.of(new PlaceOrderRequest.Line(PRODUCT, 2)));
    }

    // =============================================================================================
    // 1. The order and its event are ONE atomic fact.
    // =============================================================================================

    @Test
    @DisplayName("placing an order writes the order and its event in the same transaction")
    void orderAndEventCommitTogether() {
        var placed = orderService.place(oneLine(), CALLER);

        assertThat(orders.findByOrderKey(placed.orderKey())).isPresent();

        var events = outbox.findByAggregateIdOrderByCreatedAt(placed.orderKey());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getEventType()).isEqualTo("OrderPlaced");
        // Unpublished: the order is durable and nobody has been told yet. That intermediate state is
        // exactly what makes the guarantee possible - it is a fact in the database, not a hope.
        assertThat(events.get(0).getPublishedAt()).isNull();
    }

    @Test
    @DisplayName("THE COUNTERFACTUAL: a rollback after the append leaves neither the order nor the event")
    void rollbackLosesBoth() {
        long ordersBefore = orders.count();
        long eventsBefore = outbox.count();

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            orderService.place(oneLine(), CALLER);
            // A fault AFTER the business write and AFTER the outbox append, before commit. This is
            // the crash the dual-write bug cannot survive.
            throw new IllegalStateException("simulated crash before commit");
        })).isInstanceOf(IllegalStateException.class);

        // Neither survives, which is the correct outcome. The failure mode being ruled out is the
        // OTHER pairing: an order that exists with no event, which is unrecoverable because nothing
        // knows it needs recovering.
        assertThat(orders.count()).isEqualTo(ordersBefore);
        assertThat(outbox.count()).isEqualTo(eventsBefore);
    }

    // =============================================================================================
    // 2. A fault in the PUBLISH leaves the event recoverable, never lost.
    // =============================================================================================

    @Test
    @DisplayName("when the broker rejects the send, the event stays unpublished and records why")
    void publishFailureLeavesTheEventRecoverable() {
        var placed = orderService.place(oneLine(), CALLER);

        // THE FAULT: the broker is unreachable at the moment of publication.
        when(kafka.send(any(org.apache.kafka.clients.producer.ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        new IllegalStateException("broker unreachable")));

        int published = publisher.publishBatch();

        assertThat(published).isZero();

        var event = outbox.findByAggregateIdOrderByCreatedAt(placed.orderKey()).get(0);
        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getLastError()).contains("broker unreachable");

        // AND THE ORDER IS STILL THERE. Without the outbox this is the divergence: the order exists,
        // the send failed, and there is no record anywhere that anything is owed.
        assertThat(orders.findByOrderKey(placed.orderKey()))
                .get().extracting(o -> o.getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    @DisplayName("the same event publishes successfully on the next pass, once the broker is back")
    void theEventIsPublishedOnRecovery() {
        var placed = orderService.place(oneLine(), CALLER);

        when(kafka.send(any(org.apache.kafka.clients.producer.ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("down")));
        assertThat(publisher.publishBatch()).isZero();

        // The broker recovers. Nothing else changes - no retry queue, no operator intervention, no
        // replay tool. The unpublished row IS the queue.
        when(kafka.send(any(org.apache.kafka.clients.producer.ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        assertThat(publisher.publishBatch()).isEqualTo(1);

        var event = outbox.findByAggregateIdOrderByCreatedAt(placed.orderKey()).get(0);
        assertThat(event.getPublishedAt()).isNotNull();
        assertThat(outbox.countByPublishedAtIsNull()).isZero();
    }

    @Test
    @DisplayName("a published event is never published twice")
    void publishedEventsAreNotResent() {
        orderService.place(oneLine(), CALLER);

        assertThat(publisher.publishBatch()).isEqualTo(1);
        // A second pass finds nothing to do. The partial index the relay reads is on
        // `published_at IS NULL`, so a published row leaves the working set entirely.
        assertThat(publisher.publishBatch()).isZero();
    }

    // =============================================================================================
    // 3. The writer refuses to be used unsafely.
    // =============================================================================================

    @Test
    @DisplayName("appending outside a transaction is refused, not silently committed alone")
    void appendOutsideATransactionIsRefused() {
        // Propagation.MANDATORY. Without it, a caller that forgot @Transactional would get a brand
        // new transaction for the event alone - committing the announcement of something that never
        // happened. The dual-write bug wearing the costume of its own solution.
        assertThatThrownBy(() -> writer.append("Order", "no-tx", "OrderPlaced", "some.topic",
                Map.of("orderKey", "no-tx")))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);

        assertThat(outbox.findByAggregateIdOrderByCreatedAt("no-tx")).isEmpty();
    }

    @Test
    @DisplayName("an unserialisable payload fails the business transaction, not the relay an hour later")
    void unserialisablePayloadFailsAtWriteTime() {
        Object cyclic = new Object() {
            @SuppressWarnings("unused")
            public Object getSelf() {
                throw new UnsupportedOperationException("not serialisable");
            }
        };

        assertThatThrownBy(() -> transactions.executeWithoutResult(status ->
                writer.append("Order", "bad-payload", "Broken", "some.topic", cyclic)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(outbox.findByAggregateIdOrderByCreatedAt("bad-payload")).isEmpty();
    }

    // =============================================================================================
    // 4. Ordering.
    // =============================================================================================

    @Test
    @DisplayName("events for one aggregate keep their order, and carry the key that guarantees it")
    void eventsAreKeyedByAggregate() {
        var placed = orderService.place(oneLine(), CALLER);

        transactions.executeWithoutResult(status ->
                writer.append("Order", placed.orderKey(), "OrderConfirmed", "some.topic",
                        Map.of("orderKey", placed.orderKey())));

        var events = outbox.findByAggregateIdOrderByCreatedAt(placed.orderKey());
        assertThat(events).extracting(OutboxEvent::getEventType)
                .containsExactly("OrderPlaced", "OrderConfirmed");
        // The aggregate id is what becomes the Kafka message key, which chooses the partition. Same
        // key -> same partition -> ordering preserved. A null key round-robins, and then
        // "placed" and "confirmed" can be handled by different threads in either order.
        assertThat(events).allSatisfy(e ->
                assertThat(e.getAggregateId()).isEqualTo(placed.orderKey()));
    }
}
