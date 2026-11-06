package com.ecommerce.discovery;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.netflix.eureka.server.EnableEurekaServer;

/**
 * The service registry.
 *
 * <p>The bookstore capstone did without one: its gateway routed to {@code http://book-service:8082},
 * an address supplied by configuration. That works, and it is worth being precise about what it
 * cannot do, because "we already had config, why add Eureka" is the obvious objection.
 *
 * <p>A configured address names <em>one</em> endpoint. Run two copies of order-service and the
 * config has to name a load balancer instead — so the platform acquires a component whose job is to
 * know which instances are alive, which is what a registry is, only now it is somebody else's and
 * invisible to the application. Under Kubernetes that component is a Service and this module would
 * be redundant; on a laptop, on EC2, or anywhere the deployment substrate does not supply one, it is
 * not.
 *
 * <p>What the registry actually buys, concretely:
 * <ul>
 *   <li><strong>Instance-level, not host-level, resolution.</strong> {@code lb://order-service}
 *       resolves to the set of registered instances, and the client-side load balancer picks per
 *       <em>request</em>. A Kubernetes Service picks per <em>connection</em> — which the capstone
 *       measured going wrong: 20 requests through a pooled gateway connection all landed on one of
 *       two pods.</li>
 *   <li><strong>Health-aware removal.</strong> An instance that stops sending heartbeats is evicted,
 *       so a crashed process stops receiving traffic without anybody editing configuration.</li>
 * </ul>
 *
 * <p>The honest caveat is that Eureka is AP, not CP: it prefers to serve a possibly-stale registry
 * over refusing to answer, and self-preservation mode will keep dead instances listed rather than
 * mass-evict during a network partition. That is the right trade for service discovery — a stale
 * address fails one request, an unavailable registry fails all of them — and it is turned off below
 * for local development only because on one machine a "partition" is always really a dead process.
 */
@SpringBootApplication
@EnableEurekaServer
public class DiscoveryServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(DiscoveryServerApplication.class, args);
    }
}
