package com.ecommerce.order.exception;

/**
 * Inventory answered, and the answer was no.
 *
 * <p>A business outcome rather than a fault, which is why Resilience4j is configured to IGNORE it:
 * counting sold-out products as circuit-breaker failures would let one popular out-of-stock item
 * trip the breaker and block reservations for every other product.
 */
public class OutOfStockException extends RuntimeException {
    public OutOfStockException(String message) {
        super(message);
    }
}
