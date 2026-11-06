package com.ecommerce.order.client;

import com.ecommerce.order.exception.InventoryUnavailableException;
import com.ecommerce.order.exception.OutOfStockException;
import feign.FeignException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Reserving, releasing and confirming stock, with the resilience policy attached.
 *
 * <h2>The rule this class exists to enforce: RETRY READS, NEVER RETRY THE STOCK WRITE</h2>
 *
 * <p>A retry is safe only when the operation is idempotent. {@code reserve} <em>is</em>, but only
 * because inventory-service keys reservations on {@code (order_key, product_key)} and returns the
 * existing reservation rather than making a second one — so the retry is safe by design on the other
 * side, not by hope on this one. That is why the retry is configured here at all, and why the
 * comment says so: change inventory-service to allow two reservations per order and this annotation
 * becomes a double-reservation generator.
 *
 * <h2>Out of stock is not a failure</h2>
 *
 * <p>A 409 from inventory-service means "there are three left and you asked for ten". Retrying it is
 * pointless and letting the circuit breaker count it is actively harmful: enough customers ordering
 * a sold-out product would trip the breaker and take down reservations for every <em>other</em>
 * product. It is translated into a typed exception that Resilience4j is configured to ignore
 * (`ignore-exceptions` in config-repo/order-service.yml).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InventoryGateway {

    private final InventoryClient inventory;

    @CircuitBreaker(name = "inventory", fallbackMethod = "reserveUnavailable")
    @Retry(name = "inventory")
    public int reserve(String orderKey, List<InventoryClient.ReserveLine> lines) {
        try {
            return inventory.reserve(new InventoryClient.ReserveRequest(orderKey, lines))
                    .linesReserved();
        } catch (FeignException.Conflict conflict) {
            // The body carries productKey/requested/available; it is passed through so the customer
            // is told which line failed and how many are left, rather than "order failed".
            throw new OutOfStockException(conflict.contentUTF8());
        }
    }

    /**
     * Compensation. No circuit breaker, and that is deliberate.
     *
     * <p>A breaker exists to stop a caller hammering a failing dependency on the <em>request</em>
     * path, where failing fast protects the caller. Compensation is not on a request path: if it
     * cannot run now it must run later, and refusing to attempt it because a breaker is open leaves
     * stock reserved for an order that no longer exists. Retry, log loudly, and let the recovery job
     * try again.
     */
    @Retry(name = "inventoryCompensation")
    public int release(String orderKey) {
        try {
            return inventory.release(orderKey).linesReserved();
        } catch (RuntimeException ex) {
            // Swallowed after retries, and this is the one place a swallowed exception is correct:
            // the caller is already handling a failure, and throwing here would replace a useful
            // "your order was cancelled" with an opaque 500. The stuck reservation is picked up by
            // inventory's own sweeper.
            log.error("COMPENSATION FAILED for order {}: stock may stay reserved. {}",
                    orderKey, ex.toString());
            return 0;
        }
    }

    @Retry(name = "inventoryCompensation")
    public int confirm(String orderKey) {
        try {
            return inventory.confirm(orderKey).linesReserved();
        } catch (RuntimeException ex) {
            log.error("Confirming reservations for order {} failed: {}", orderKey, ex.toString());
            return 0;
        }
    }

    @SuppressWarnings("unused")
    private int reserveUnavailable(String orderKey, List<InventoryClient.ReserveLine> lines,
                                   Throwable cause) {
        log.error("inventory-service unavailable while reserving for order {}: {}",
                orderKey, cause.toString());
        throw new InventoryUnavailableException(
                "Stock could not be reserved right now. Please retry.", cause);
    }
}
