package com.ecommerce.catalog.dto;

import com.ecommerce.catalog.entity.Product;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * The API boundary.
 *
 * <p>Entities never leave the service layer. Three reasons, in order of how often they bite:
 *
 * <ol>
 *   <li><strong>Serialising an entity serialises its lazy associations</strong>, which either
 *       triggers N+1 queries during JSON writing or throws {@code LazyInitializationException} —
 *       and with {@code open-in-view: false} (which is the correct setting) it is always the
 *       second.</li>
 *   <li><strong>The database schema becomes the API contract.</strong> Rename a column and every
 *       client breaks, so the schema stops being changeable.</li>
 *   <li><strong>Everything is exposed by default.</strong> A field added for internal bookkeeping
 *       appears in the public response the moment it is added, which is how internal state leaks.</li>
 * </ol>
 */
public final class ProductDtos {

    private ProductDtos() {}

    /**
     * What a client sees.
     *
     * <p>{@code Serializable} because this is what goes into Redis in Step 6, and the default
     * {@code JdkSerializationRedisSerializer} refuses anything that is not. It is serialised as JSON
     * in practice (see {@code CacheConfig}) — the interface is kept so a misconfiguration fails at
     * the cache write rather than producing an unreadable blob.
     *
     * <p>No {@code version} field. Optimistic locking is this service's business, not a client's,
     * and exposing it would invite a client to send it back and expect it to be honoured — which is
     * a different concurrency contract (`If-Match`) than the one implemented here.
     */
    public record ProductResponse(
            Long id,
            String productKey,
            String name,
            String category,
            BigDecimal price,
            Integer weightGrams,
            Integer lengthCm,
            Integer heightCm,
            Integer widthCm,
            Integer photosQty,
            boolean active
    ) implements Serializable {

        public static ProductResponse from(Product p) {
            return new ProductResponse(p.getId(), p.getProductKey(), p.getName(), p.getCategory(),
                    p.getPrice(), p.getWeightGrams(), p.getLengthCm(), p.getHeightCm(),
                    p.getWidthCm(), p.getPhotosQty(), p.isActive());
        }
    }

    /** Create/update payload. Deliberately smaller than the response: ids and audit stamps are ours. */
    public record ProductRequest(
            @NotBlank @Size(max = 64) String productKey,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 100) String category,
            @DecimalMin(value = "0.0", inclusive = false, message = "price must be greater than 0")
            BigDecimal price,
            Integer weightGrams,
            Integer lengthCm,
            Integer heightCm,
            Integer widthCm,
            Integer photosQty
    ) {}

    /**
     * A page, without Spring's {@code Page} on the wire.
     *
     * <p>{@code PageImpl} serialises to a JSON shape Spring itself warns is unstable across
     * versions, and it carries a `pageable` object full of internals no client needs. Four numbers
     * and a list is the contract.
     */
    public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
        public static <T> PageResponse<T> of(org.springframework.data.domain.Page<T> page) {
            return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                    page.getTotalElements(), page.getTotalPages());
        }
    }

    /**
     * Bulk lookup by natural key, used by order-service when it prices a cart.
     *
     * <p>One round trip for N products rather than N round trips. The obvious alternative — order
     * -service calling {@code GET /api/products/{key}} in a loop — turns a 10-line cart into 10
     * sequential HTTP calls, each with its own timeout budget, and makes checkout latency linear in
     * cart size.
     */
    public record BulkLookupRequest(List<String> productKeys) {}
}
