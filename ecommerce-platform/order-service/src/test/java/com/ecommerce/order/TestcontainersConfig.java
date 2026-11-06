package com.ecommerce.order;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * A real PostgreSQL, started by the test run and discarded when it ends.
 *
 * <p>{@code @ServiceConnection} is what makes this a two-line affair: Spring Boot reads the
 * container's host, port, database and credentials once it is running and wires the DataSource from
 * them. No {@code @DynamicPropertySource}, no properties to keep in sync with the compose file.
 *
 * <p><strong>The version is pinned to match production.</strong> Testing against a different
 * database than you deploy on is testing something else — and the same image tag as
 * {@code docker-compose.yml} means a behaviour difference between local and CI is a real difference,
 * not a version skew. It matters here specifically: the browse query relies on Postgres inferring
 * parameter types through {@code IS NULL} comparisons, and H2 would have accepted a query Postgres
 * rejects.
 *
 * <p>Spring caches the application context across test classes that share the same configuration, so
 * the container starts once for the whole suite rather than once per class.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>("postgres:17-alpine");
    }
}
