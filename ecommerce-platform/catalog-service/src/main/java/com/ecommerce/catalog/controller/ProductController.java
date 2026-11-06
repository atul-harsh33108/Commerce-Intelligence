package com.ecommerce.catalog.controller;

import com.ecommerce.catalog.dto.ProductDtos.BulkLookupRequest;
import com.ecommerce.catalog.dto.ProductDtos.PageResponse;
import com.ecommerce.catalog.dto.ProductDtos.ProductRequest;
import com.ecommerce.catalog.dto.ProductDtos.ProductResponse;
import com.ecommerce.catalog.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.net.URI;
import java.util.List;

/**
 * HTTP for the catalogue.
 *
 * <p>Resources are nouns, verbs are HTTP methods, and status codes carry meaning: 200 read, 201 with
 * a {@code Location} on create, 204 on delete, 404 missing, 409 conflict, 400 validation. That is
 * the whole REST contract and it is worth keeping strictly, because every deviation is a special
 * case a client has to be told about out of band.
 *
 * <p>Thin by design: it validates, delegates, and chooses a status code. Business rules live in the
 * service, which is what makes them unit-testable without a servlet container and reusable from a
 * Kafka listener.
 */
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService products;

    @GetMapping("/{id}")
    public ProductResponse byId(@PathVariable Long id) {
        return products.getById(id);
    }

    /**
     * Lookup by the Olist natural key.
     *
     * <p>A separate path segment rather than "try {@code /{id}} and fall back", because
     * {@code /api/products/6cdd53843498f92890544667809f1595} would otherwise have to be
     * distinguished from a numeric id by parsing — and a 32-character hex string that happens to
     * start with digits is exactly the input that makes that go wrong.
     */
    @GetMapping("/key/{productKey}")
    public ProductResponse byKey(@PathVariable String productKey) {
        return products.getByKey(productKey);
    }

    @PostMapping("/bulk")
    public List<ProductResponse> bulk(@RequestBody BulkLookupRequest request) {
        return products.getByKeys(request.productKeys());
    }

    /**
     * Browse.
     *
     * <p>{@code size = 20} and a default sort, because an unbounded list endpoint over 32K rows is a
     * denial-of-service vector that looks like a convenience. Spring's own default page size is 20;
     * it is stated here so it is a decision rather than a default that could change.
     */
    @GetMapping
    public PageResponse<ProductResponse> browse(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) BigDecimal maxPrice,
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.ASC) Pageable pageable) {
        return products.browse(category, maxPrice, pageable);
    }

    @PostMapping
    public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductRequest request) {
        ProductResponse created = products.create(request);
        return ResponseEntity.created(URI.create("/api/products/" + created.id())).body(created);
    }

    @PutMapping("/{id}")
    public ProductResponse update(@PathVariable Long id, @Valid @RequestBody ProductRequest request) {
        return products.update(id, request);
    }

    /**
     * Queue a ProductUpdated for every product, to rebuild a downstream read model.
     *
     * <p>ADMIN only, and rightly: it writes 32,951 outbox rows and saturates the relay for a few
     * minutes. It is an operational action, not a feature.
     */
    @PostMapping("/reindex")
    public java.util.Map<String, Long> reindex(
            @RequestParam(defaultValue = "500") int batchSize) {
        return java.util.Map.of("queued", products.reindexAll(Math.min(Math.max(batchSize, 1), 2000)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        products.delete(id);
        return ResponseEntity.noContent().build();
    }
}
