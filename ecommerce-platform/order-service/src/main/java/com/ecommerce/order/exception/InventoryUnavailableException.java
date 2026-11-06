package com.ecommerce.order.exception;

/** Stock could not be reserved because inventory-service was unreachable. 503, not 500. */
public class InventoryUnavailableException extends RuntimeException {
    public InventoryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
