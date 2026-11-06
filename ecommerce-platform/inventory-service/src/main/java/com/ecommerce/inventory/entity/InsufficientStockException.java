package com.ecommerce.inventory.entity;

import lombok.Getter;

/**
 * Not enough available stock to satisfy a reservation.
 *
 * <p>A business outcome, not a fault: it becomes a 409 over HTTP and an {@code InventoryFailed}
 * event over Kafka, which is what makes the saga compensate rather than retry. Distinguishing it
 * from a genuine error matters — a retryable failure (database blip) and a deterministic one (there
 * are three left and you want ten) want opposite treatment, and a consumer that retries the second
 * one stalls its partition forever.
 */
@Getter
public class InsufficientStockException extends RuntimeException {

    private final String productKey;
    private final int requested;
    private final int available;

    public InsufficientStockException(String productKey, int requested, int available) {
        super("Insufficient stock for %s: requested %d, available %d"
                .formatted(productKey, requested, available));
        this.productKey = productKey;
        this.requested = requested;
        this.available = available;
    }
}
