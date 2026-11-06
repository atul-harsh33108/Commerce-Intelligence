package com.ecommerce.order.exception;

/**
 * The caller may not act on this order.
 *
 * <p>403, and deliberately NOT 404. Hiding the existence of somebody else's order behind a 404 is a
 * defensible choice in some systems; here the id is a UUID that cannot be guessed, so the extra
 * obscurity buys nothing and the honest status is more useful to a client that has a stale link.
 */
public class OrderNotAllowedException extends RuntimeException {
    public OrderNotAllowedException(String message) {
        super(message);
    }
}
