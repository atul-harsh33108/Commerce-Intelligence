package com.ecommerce.order.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.converter.ConversionException;
import org.springframework.kafka.support.converter.RecordMessageConverter;
import org.springframework.kafka.support.converter.StringJsonMessageConverter;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Kafka wiring for order-service: the topics it owns, how it reads other people's, and what happens
 * when a message cannot be processed.
 */
@Configuration
@Slf4j
public class KafkaConfig {

    // ---------------------------------------------------------------------------------------------
    // Topics this service OWNS.
    //
    // The producer declares the topic, not the consumers. A topic belongs to whoever publishes to
    // it, the same way a table belongs to the service that writes it - consumers come and go and
    // none of them should be able to change its shape. The broker runs with
    // auto.create.topics.enable=false, so a typo here is a startup failure rather than a brand new
    // topic that nothing reads.
    // ---------------------------------------------------------------------------------------------

    /**
     * Three partitions, so that keying by order id decides something.
     *
     * <p>With one partition every message is ordered relative to every other and the key is
     * decoration; with three, events for one order still arrive in order while unrelated orders are
     * processed in parallel — which is the property the key buys and the reason to key at all.
     *
     * <p>Partition count is close to permanent: raising it later re-hashes keys onto different
     * partitions, so one key's history is split across two of them and per-key ordering is broken
     * for everything already written. It also bounds consumer parallelism — a group can never have
     * more useful members than the topic has partitions.
     */
    @Bean
    public NewTopic orderPlacedTopic(@Value("${app.kafka.topics.order-placed}") String name) {
        return TopicBuilder.name(name).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic orderConfirmedTopic(@Value("${app.kafka.topics.order-confirmed}") String name) {
        return TopicBuilder.name(name).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic orderCancelledTopic(@Value("${app.kafka.topics.order-cancelled}") String name) {
        return TopicBuilder.name(name).partitions(3).replicas(1).build();
    }

    // ---------------------------------------------------------------------------------------------
    // Consuming.
    // ---------------------------------------------------------------------------------------------

    /**
     * How bytes become an event record.
     *
     * <p>The obvious setup — {@code JsonDeserializer} on the consumer — does not survive the service
     * split, and the way it fails looks like a serialization bug and is not.
     * {@code JsonSerializer} writes the producer's fully-qualified class name into a
     * {@code __TypeId__} header and {@code JsonDeserializer} obeys it, so inventory-service would be
     * instructing this one to instantiate {@code com.ecommerce.inventory.event.InventoryReserved} —
     * a class that does not exist here and must not (D2). The fix people reach for is a shared jar
     * of event classes.
     *
     * <p>Type headers are switched off at the producer and the type is decided <em>here</em>, by the
     * signature of each {@code @KafkaListener} method. The consumer chooses how to interpret the
     * bytes, which is the correct division of authority and is what lets this service read four
     * topics carrying four event types.
     */
    @Bean
    public RecordMessageConverter jsonMessageConverter() {
        // Jackson's FAIL_ON_UNKNOWN_PROPERTIES is off by Boot's default, which is what makes a
        // producer adding a field an additive change rather than an outage in a consumer nobody
        // rebuilt. Worth knowing it is a default and not a decision anyone here made: a project that
        // turns it on globally for stricter API parsing breaks every consumer on the platform.
        return new StringJsonMessageConverter();
    }

    /**
     * What happens when a message cannot be processed.
     *
     * <h2>The poison-message problem</h2>
     *
     * <p>Kafka gives ordering within a partition and the container honours it: a record that keeps
     * throwing is retried forever and <strong>every later record on that partition waits behind
     * it</strong>. One malformed message stops a third of this service's saga transitions
     * indefinitely, and the only symptom is a consumer that has gone quiet.
     *
     * <h2>The DLT is named per consumer group</h2>
     *
     * <p>Spring's default is {@code <topic>.DLT}. Several services read the same topics here, so the
     * default would pour every service's failures into one place — and then "how many orders are
     * stuck?" cannot be answered without inspecting each record, and a replay tool would re-deliver
     * payment's failures to order-service looking for its own.
     */
    @Bean
    public DefaultErrorHandler deadLetterErrorHandler(
            KafkaTemplate<Object, Object> template,
            @Value("${app.kafka.groups.order}") String group) {

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, exception) -> new TopicPartition(
                        deadLetterNameFor(record.topic(), group), record.partition()));

        // Small on purpose. This is not the last line of defence - the DLT is - and a long retry
        // budget converts "one bad message" into "this partition is minutes behind", which is the
        // same outage arriving more slowly.
        ExponentialBackOff backOff = new ExponentialBackOff(500, 2.0);
        backOff.setMaxAttempts(3);
        backOff.setMaxInterval(4000);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);

        // A payload that cannot be turned into the expected type will not become one on the fourth
        // attempt. Straight to the DLT, and the partition keeps moving.
        handler.addNotRetryableExceptions(
                ConversionException.class,
                MessageConversionException.class,
                IllegalArgumentException.class);

        handler.setRetryListeners((record, exception, deliveryAttempt) ->
                log.warn("Attempt {} failed for {}-{} offset {}: {}",
                        deliveryAttempt, record.topic(), record.partition(), record.offset(),
                        exception.getMessage()));

        return handler;
    }

    /**
     * {@code ecommerce.payment.completed} + {@code order-service} →
     * {@code ecommerce.payment.completed.order-service.DLT}.
     */
    public static String deadLetterNameFor(String topic, String consumerGroup) {
        return topic + "." + consumerGroup + ".DLT";
    }

    // ---------------------------------------------------------------------------------------------
    // DEAD LETTER TOPICS.
    //
    // Declared here because this service's consumers PRODUCE to them - which is the same ownership
    // rule as everywhere else: the writer declares the topic. The topics being consumed are declared
    // by whoever publishes them.
    //
    // Missing these is not a graceful degradation. With auto-creation off, DeadLetterPublishingRecoverer
    // blocks for the full 60 s metadata timeout per attempt and then fails, so a single poison message
    // stops the partition indefinitely and the log talks about a missing topic rather than about the
    // record that could not be processed. Found by running the saga, not by reading the docs.
    // ---------------------------------------------------------------------------------------------

    // KafkaAdmin.NewTopics, NOT a List<NewTopic>.
    //
    // KafkaAdmin scans the context for beans of type NewTopic and NewTopics, and for nothing else. A
    // `List<NewTopic>` bean is created, injected nowhere, and silently ignored - which is how the
    // first attempt at this "declared" three DLTs that did not exist, leaving the recoverer to block
    // for its full 60 s metadata timeout on every poison message.
    @Bean
    public org.springframework.kafka.core.KafkaAdmin.NewTopics deadLetterTopics(
            @Value("${app.kafka.groups.order}") String group,
            org.springframework.core.env.Environment env) {

        NewTopic[] topics = CONSUMED.stream()
                .map(key -> env.getProperty("app.kafka.topics." + key))
                .filter(java.util.Objects::nonNull)
                .map(topic -> TopicBuilder.name(deadLetterNameFor(topic, group))
                        .partitions(3).replicas(1).build())
                .toArray(NewTopic[]::new);
        return new org.springframework.kafka.core.KafkaAdmin.NewTopics(topics);
    }

    /** The topics this service consumes, by config key. */
    private static final java.util.List<String> CONSUMED = java.util.List.of("inventory-reserved", "inventory-failed", "payment-completed", "payment-failed");

}
