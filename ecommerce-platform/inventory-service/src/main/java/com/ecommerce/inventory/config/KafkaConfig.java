package com.ecommerce.inventory.config;

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
 * Kafka wiring for inventory-service: the topics it owns, how it reads other people's, and what happens when
 * a message cannot be processed.
 *
 * <p>The producer declares its own topics and nobody else's - a topic belongs to whoever publishes
 * to it, the same way a table belongs to the service that writes it. The broker runs with
 * auto.create.topics.enable=false, so a typo is a startup failure rather than a new topic that
 * nothing reads.
 */
@Configuration
@Slf4j
public class KafkaConfig {

    /**
     * Three partitions, so that keying by order id decides something.
     *
     * <p>With one partition every message is ordered relative to every other and the key is
     * decoration; with three, events for one order still arrive in order while unrelated orders are
     * processed in parallel. Partition count is close to permanent - raising it re-hashes keys onto
     * different partitions and breaks per-key ordering for everything already written.
     */
    @Bean
    public NewTopic inventoryReservedTopic(
            @Value("${app.kafka.topics.inventory-reserved}") String name) {
        return TopicBuilder.name(name).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic inventoryFailedTopic(
            @Value("${app.kafka.topics.inventory-failed}") String name) {
        return TopicBuilder.name(name).partitions(3).replicas(1).build();
    }

    /**
     * How bytes become an event record.
     *
     * <p>Type headers are off at the producer, so the type is decided here by each
     * {@code @KafkaListener} method's own parameter type. With them on, {@code JsonSerializer}
     * writes the producer's fully-qualified class name and the consumer tries to instantiate a class
     * that does not exist in its classpath - which is what pushes people towards a shared events jar
     * (D2). A producer publishes facts, not Java types.
     */
    @Bean
    public RecordMessageConverter jsonMessageConverter() {
        return new StringJsonMessageConverter();
    }

    /**
     * Retry a few times, then get out of the way.
     *
     * <p>Kafka gives ordering within a partition and the container honours it: a record that keeps
     * throwing is retried forever and every later record on that partition waits behind it. One
     * malformed message therefore stops a third of this service's work, and the only symptom is a
     * consumer that has gone quiet.
     *
     * <p>The DLT is named per CONSUMER GROUP, not per topic. Spring's default is
     * {@code <topic>.DLT}, and several services read the same topics here - so the default would
     * pour every service's failures into one place and "how many of MY messages are stuck" could
     * only be answered by inspecting each record.
     */
    @Bean
    public DefaultErrorHandler deadLetterErrorHandler(
            KafkaTemplate<Object, Object> template,
            @Value("${app.kafka.groups.inventory}") String group) {

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, exception) -> new TopicPartition(
                        deadLetterNameFor(record.topic(), group), record.partition()));

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
            @Value("${app.kafka.groups.inventory}") String group,
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
    private static final java.util.List<String> CONSUMED = java.util.List.of("order-placed", "payment-completed", "order-cancelled");

}
