package com.ecommerce.search.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.converter.ConversionException;
import org.springframework.kafka.support.converter.RecordMessageConverter;
import org.springframework.kafka.support.converter.StringJsonMessageConverter;
import org.springframework.messaging.converter.MessageConversionException;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Kafka wiring for search-service.
 *
 * <p>It declares NO topic it publishes to, because it publishes nothing - a read model is a leaf.
 * The only topic it creates is its own dead-letter destination, following the same rule as
 * everywhere else: the producer declares the topic, and for a DLT the producer is this service's own
 * error handler.
 */
@Configuration
@Slf4j
public class KafkaConfig {

    @Bean
    public RecordMessageConverter jsonMessageConverter() {
        return new StringJsonMessageConverter();
    }

    @Bean
    public KafkaAdmin.NewTopics searchDeadLetterTopics(
            @Value("${app.kafka.topics.product-updated}") String topic,
            @Value("${app.kafka.groups.search}") String group) {
        return new KafkaAdmin.NewTopics(
                TopicBuilder.name(deadLetterNameFor(topic, group))
                        .partitions(3).replicas(1).build());
    }

    /**
     * Retry, then get out of the way.
     *
     * <p>A poison message here is less dangerous than elsewhere - the worst case is one product
     * missing from search - and the partition-blocking behaviour is identical, so the same DLT
     * treatment applies. An index that is quietly one product short is exactly the kind of failure
     * nobody notices, which is why the DLT is named per consumer group and can be counted.
     */
    @Bean
    public DefaultErrorHandler deadLetterErrorHandler(
            KafkaTemplate<Object, Object> template,
            @Value("${app.kafka.groups.search}") String group) {

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, exception) -> new TopicPartition(
                        deadLetterNameFor(record.topic(), group), record.partition()));

        ExponentialBackOff backOff = new ExponentialBackOff(500, 2.0);
        backOff.setMaxAttempts(3);
        backOff.setMaxInterval(4000);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(
                ConversionException.class, MessageConversionException.class,
                IllegalArgumentException.class);
        handler.setRetryListeners((record, exception, attempt) ->
                log.warn("Attempt {} failed for {}-{} offset {}: {}", attempt, record.topic(),
                        record.partition(), record.offset(), exception.getMessage()));
        return handler;
    }

    public static String deadLetterNameFor(String topic, String consumerGroup) {
        return topic + "." + consumerGroup + ".DLT";
    }
}
