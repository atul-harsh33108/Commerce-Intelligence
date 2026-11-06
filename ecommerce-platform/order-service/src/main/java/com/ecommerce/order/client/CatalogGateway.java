package com.ecommerce.order.client;

import com.ecommerce.order.exception.CatalogUnavailableException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Everything order-service knows about calling the catalogue, in one place.
 *
 * <h2>Why a wrapper and not annotations on the Feign interface</h2>
 *
 * <p>Resilience4j's annotations work on Spring beans, and a {@code @FeignClient} interface is a
 * proxy — putting {@code @CircuitBreaker} there is a well-known way to have the annotation silently
 * do nothing. A plain component that the aspects can actually wrap is both correct and honest about
 * what is happening.
 *
 * <h2>The fallback fails fast; it does not invent data</h2>
 *
 * <p>The tempting fallback for "the catalogue is down" is to return an empty list or a zero price
 * and let the order proceed. <strong>That is worse than failing.</strong> An order priced at zero
 * because a dependency was unreachable is a real financial loss that looks like a successful
 * checkout, and it will not be noticed until reconciliation. The fallback here throws a typed
 * exception that becomes a 503 with {@code Retry-After} — the customer is told to try again, which
 * is true, and nothing wrong is written down.
 *
 * <p>A fallback that degrades gracefully is right when the missing data is <em>decorative</em> (a
 * recommendation strip, a review count). It is wrong when the missing data is the price.
 *
 * <h2>Aspect order</h2>
 *
 * <p>CircuitBreaker outside Retry, set platform-wide in config-repo/application.yml. The default is
 * the other way round, and it produces two problems: one retried call records three failures against
 * the breaker (so the breaker trips three times too eagerly), and an open breaker's refusal
 * propagates out to Retry, which then retries being told "no" through the full backoff — the
 * capstone measured "fail fast" taking 619 ms.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CatalogGateway {

    private final CatalogClient catalog;

    @CircuitBreaker(name = "catalog", fallbackMethod = "unavailable")
    @Retry(name = "catalog")
    public Map<String, CatalogClient.ProductSnapshot> priceLookup(List<String> productKeys) {
        List<CatalogClient.ProductSnapshot> found =
                catalog.lookup(new CatalogClient.BulkLookupRequest(productKeys));

        return found.stream().collect(java.util.stream.Collectors.toMap(
                CatalogClient.ProductSnapshot::productKey, Function.identity(),
                // A duplicate key from the catalogue would be a bug there, not here; keeping the
                // first is arbitrary but deterministic, which beats an IllegalStateException from
                // Collectors.toMap's default merge on a checkout path.
                (a, b) -> a));
    }

    /**
     * The fallback signature must end with the Throwable, and must otherwise match.
     *
     * <p>A mismatch is not a compile error — Resilience4j resolves it reflectively at runtime and
     * logs "fallback method not found", after which the original exception propagates and the
     * fallback appears not to exist. Worth knowing, because the failure looks like the annotation
     * being ignored.
     */
    @SuppressWarnings("unused")
    private Map<String, CatalogClient.ProductSnapshot> unavailable(List<String> productKeys,
                                                                   Throwable cause) {
        log.error("catalog-service unavailable while pricing {} product(s): {}",
                productKeys.size(), cause.toString());
        throw new CatalogUnavailableException(
                "The product catalogue is temporarily unavailable. Please retry.", cause);
    }
}
