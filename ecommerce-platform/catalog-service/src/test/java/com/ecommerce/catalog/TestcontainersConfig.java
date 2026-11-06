package com.ecommerce.catalog;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The real infrastructure these tests run against, started per run and thrown away.
 *
 * <p>Both containers use the same image tags as {@code docker-compose.yml}. A test that passes
 * against Postgres 16 and a service that runs on 17 is a test of something nobody deploys.
 *
 * <h2>Why Redis is here, and what its absence looked like</h2>
 *
 * <p>Redis was added after CI failed on it, and the failure is worth recording because the tests had
 * been "passing" locally for five steps.
 *
 * <p>{@code @Cacheable} on {@code getById}/{@code getByKey} means every read path performs a cache
 * lookup, so the tests need a Redis whether or not they assert anything about caching. On this
 * machine one was always listening on 6379 — the container from {@code docker-compose up} — so the
 * tests quietly used a service that nothing in the test configuration declared. On a runner with no
 * such container, five of the thirteen failed with {@code Unable to connect to Redis}.
 *
 * <p>That is the same defect as the manifests step that only validated because a kubeconfig existed
 * here, and as {@code cache_benchmark.py} reporting a healthy cache while every hit threw: <b>a check
 * whose passing result was a property of this machine rather than of the code.</b> The fix in every
 * case is to make the dependency explicit and owned by the thing that needs it.
 *
 * <h2>Why {@code GenericContainer} rather than a Redis-specific module</h2>
 *
 * <p>{@code com.redis:testcontainers-redis} would work and is a third-party dependency to add,
 * track and upgrade. Spring Boot's {@code @ServiceConnection} recognises any container built from an
 * image named {@code redis} and produces the {@code RedisConnectionDetails} the auto-configuration
 * needs — so the core Testcontainers artifact, already on the classpath, is enough.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>("postgres:17-alpine");
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379);
    }
}
