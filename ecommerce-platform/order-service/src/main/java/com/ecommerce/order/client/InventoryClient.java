package com.ecommerce.order.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

@FeignClient(name = "inventory-service", configuration = FeignAuthPropagation.class)
public interface InventoryClient {

    record ReserveLine(String productKey, int quantity) {}

    record ReserveRequest(String orderKey, List<ReserveLine> lines) {}

    record ReserveResponse(String orderKey, int linesReserved) {}

    @PostMapping("/api/inventory/reserve")
    ReserveResponse reserve(@RequestBody ReserveRequest request);

    /**
     * The compensating action.
     *
     * <p>Called when a saga step after the reservation fails. It is idempotent on the inventory side
     * (a reservation already RELEASED is skipped), which matters because compensation is the code
     * most likely to run twice: it fires on a failure, and failures come with retries.
     */
    @PostMapping("/api/inventory/release/{orderKey}")
    ReserveResponse release(@PathVariable String orderKey);

    @PostMapping("/api/inventory/confirm/{orderKey}")
    ReserveResponse confirm(@PathVariable String orderKey);
}
