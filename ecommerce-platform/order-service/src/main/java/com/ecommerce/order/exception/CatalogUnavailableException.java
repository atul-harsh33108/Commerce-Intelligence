package com.ecommerce.order.exception;

/**
 * The catalogue could not be reached, so the order could not be priced.
 *
 * <p>Becomes a 503 with {@code Retry-After}, never a 500 and never a partially-priced order. The
 * distinction from a 500 matters to the caller: nothing is broken, a dependency is briefly away, and
 * retrying is the right thing to do.
 */
public class CatalogUnavailableException extends RuntimeException {
    public CatalogUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
