package com.ecommerce.catalog.config;

import com.ecommerce.catalog.dto.ProductDtos.ProductResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.List;

/**
 * The Redis cache manager, and one serializer per cache.
 *
 * <h2>The bug that produced this shape</h2>
 *
 * <p>This started as one default serializer for every cache:
 * {@code new GenericJackson2JsonRedisSerializer(mapper)}, passing an {@code ObjectMapper} that had
 * {@code JavaTimeModule} registered so that {@code Instant} would serialise.
 *
 * <p>That constructor is a trap. {@code GenericJackson2JsonRedisSerializer}'s <em>no-argument</em>
 * constructor builds a mapper with default typing switched on, which is what writes the
 * {@code "@class"} property that tells the deserialiser what to rebuild. Hand it your own mapper and
 * it uses that mapper exactly as given — so nothing wrote {@code @class}, and every read came back as
 * a {@code LinkedHashMap}:
 *
 * <pre>
 *   GET /api/products/5   ->  200      (miss: loaded from Postgres, then written to Redis)
 *   GET /api/products/5   ->  500      (hit:  ClassCastException, LinkedHashMap -> ProductResponse)
 *   GET /api/products/5   ->  500      ... for the whole 10-minute TTL
 * </pre>
 *
 * <p>Every cache <em>hit</em> failed. Only misses worked, which is the exact inverse of a cache.
 *
 * <h2>Why it survived a benchmark that said 100% hit rate</h2>
 *
 * <p>Two independent measurement mistakes, and neither was in this file:
 *
 * <ul>
 *   <li>{@code scripts/cache_benchmark.py} caught {@code HTTPError}, read the body to keep the
 *       connection tidy, and returned the elapsed time anyway. A 500 is fast. The latency histogram
 *       was real and described error responses.</li>
 *   <li>The hit-rate figure came from Micrometer's {@code cache.gets{result=hit}}, which counts the
 *       lookup, not what happened to the value afterwards. Redis really did return the bytes. The
 *       cast that failed was downstream of everything being counted.</li>
 * </ul>
 *
 * <p>And the error was invisible even to a person looking at it: {@code /error} was not permitted in
 * this service's filter chain, so Spring's internal forward to the error handler was rejected and the
 * 500 was rendered as {@code 401 "Authentication required"} on a public endpoint. The response gave
 * the wrong status, the wrong reason, and pointed at the wrong subsystem.
 *
 * <h2>Why per-cache types rather than turning default typing back on</h2>
 *
 * <p>The one-line fix is {@code activateDefaultTyping(...)}, which restores {@code @class}. It also
 * means the deserialiser instantiates whatever class name it finds in the cache, which is the shape
 * of every Jackson deserialisation-gadget CVE — and a cache is a place where a value's provenance is
 * whatever last wrote to Redis.
 *
 * <p>Each of these caches holds exactly one type, which is known here at compile time, so nothing
 * needs to be inferred at runtime. A typed serializer per cache costs three lines, writes smaller
 * entries (no {@code @class}), and cannot be told what class to build.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String PRODUCT_BY_ID = "product:id";
    public static final String PRODUCT_BY_KEY = "product:key";
    public static final String CATEGORIES = "catalog:categories";

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory,
                                          @Value("${app.catalog.cache-ttl:PT10M}") Duration ttl) {

        ObjectMapper mapper = new ObjectMapper()
                // Instants and other java.time types are not serialisable by a bare ObjectMapper,
                // and the failure is a runtime InvalidDefinitionException on the first cache write -
                // i.e. on the first request, in production, not at startup.
                .registerModule(new JavaTimeModule());

        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ttl)
                // DO NOT CACHE NULLS. A cached null for every unknown id turns "product not found"
                // into an unbounded set of keys an attacker can create by guessing - cache
                // poisoning by enumeration. The cost is that a genuinely missing product hits the
                // database every time, which is the correct trade for a 404.
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                // A prefix, so `redis-cli --scan --pattern 'catalog::*'` shows exactly this
                // service's keys. Redis has no namespaces; a shared instance without prefixes is one
                // key collision away from two services reading each other's data.
                .prefixCacheNameWith("catalog::");

        RedisSerializer<ProductResponse> product = new Jackson2JsonRedisSerializer<>(mapper, ProductResponse.class);
        RedisSerializer<List> categories = new Jackson2JsonRedisSerializer<>(mapper,
                TypeFactory.defaultInstance().constructCollectionType(List.class, String.class));

        return RedisCacheManager.builder(connectionFactory)
                // Off by default, and required for Micrometer to publish `cache.gets{result=hit}`.
                // Necessary but, as the header comment records, nowhere near sufficient: a hit is
                // counted before anything looks at the value.
                .enableStatistics()
                .cacheDefaults(defaults)
                .withCacheConfiguration(PRODUCT_BY_ID, withValue(defaults, product))
                .withCacheConfiguration(PRODUCT_BY_KEY, withValue(defaults, product))
                .withCacheConfiguration(CATEGORIES,
                        // Longer, because the category vocabulary is a closed set from the source
                        // data and changes only when the catalogue is reloaded.
                        withValue(defaults.entryTtl(Duration.ofHours(1)), categories))
                .build();
    }

    private static RedisCacheConfiguration withValue(RedisCacheConfiguration base, RedisSerializer<?> serializer) {
        return base.serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(serializer));
    }
}
