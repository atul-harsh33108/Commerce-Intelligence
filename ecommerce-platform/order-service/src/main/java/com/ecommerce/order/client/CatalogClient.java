package com.ecommerce.order.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.math.BigDecimal;
import java.util.List;

/**
 * The catalogue, as order-service sees it.
 *
 * <p>{@code name = "catalog-service"} with no {@code url}: Feign resolves the name through the same
 * Eureka registry and load balancer the gateway uses, so a second catalog instance is used
 * immediately with no configuration change. A hard-coded URL here would make this service the one
 * place that has to be redeployed when the catalogue moves.
 *
 * <h2>Why the bulk endpoint</h2>
 *
 * <p>One call for the whole cart. The obvious alternative — {@code GET /api/products/{key}} in a
 * loop — makes checkout latency linear in cart size, and each call carries its own connect and read
 * timeout, so a 10-line cart has ten chances to hit a 3-second read timeout instead of one.
 *
 * <h2>What is deliberately NOT here</h2>
 *
 * <p>No stock query. Availability lives in inventory-service and is answered by the reservation
 * itself — asking "is there stock?" and then reserving is a check-then-act race, and the answer
 * would be stale by the time it arrived. The reservation either succeeds or reports what is
 * available.
 */
@FeignClient(name = "catalog-service", configuration = FeignAuthPropagation.class)
public interface CatalogClient {

    /**
     * The response shape, redeclared here rather than imported from catalog-service (D2).
     *
     * <p>It is deliberately narrower than what catalog-service returns: this service needs a key, a
     * name for the receipt, and a price. Jackson ignores unknown properties by default, so the
     * catalogue can add fields without breaking this — which is exactly the loose coupling a shared
     * DTO jar would remove.
     */
    record ProductSnapshot(String productKey, String name, String category, BigDecimal price,
                           boolean active) {}

    record BulkLookupRequest(List<String> productKeys) {}

    @PostMapping("/api/products/bulk")
    List<ProductSnapshot> lookup(@RequestBody BulkLookupRequest request);
}
