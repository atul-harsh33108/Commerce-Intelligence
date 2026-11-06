package com.ecommerce.catalog.service;

import com.ecommerce.catalog.dto.ProductDtos.PageResponse;
import com.ecommerce.catalog.dto.ProductDtos.ProductRequest;
import com.ecommerce.catalog.dto.ProductDtos.ProductResponse;
import com.ecommerce.catalog.entity.Product;
import com.ecommerce.catalog.exception.ResourceNotFoundException;
import com.ecommerce.catalog.config.CacheConfig;
import com.ecommerce.catalog.event.Events;
import com.ecommerce.catalog.outbox.OutboxWriter;
import com.ecommerce.catalog.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductServiceImpl implements ProductService {

    private final ProductRepository products;
    private final OutboxWriter outbox;

    @Value("${app.kafka.topics.product-updated}")
    private String productUpdatedTopic;

    /**
     * Announces a product's current state, in the SAME transaction as the change.
     *
     * <p>The same reasoning as the order saga (D8): a database write and a Kafka send cannot be one
     * atomic act, so the event goes into an outbox row and a relay publishes it. Without that, a
     * catalogue update that commits while the broker is unreachable leaves the search index showing
     * the old price forever - with no error, because from the catalogue's point of view the write
     * succeeded.
     *
     * <p>The event carries the whole product rather than an id, which is what lets search-service be
     * rebuilt from a replay instead of having to call back for every document.
     */
    private void announce(Product p) {
        outbox.append("Product", p.getProductKey(), "ProductUpdated", productUpdatedTopic,
                new Events.ProductUpdated(
                        p.getProductKey(), p.getId(), p.getName(), p.getCategory(),
                        p.getCategoryKey(), p.getPrice(), p.getWeightGrams(), p.getPhotosQty(),
                        p.isActive(), Instant.now()));
    }

    /**
     * {@code readOnly = true} on every read path.
     *
     * <p>Not a micro-optimisation. It tells Hibernate to skip dirty checking (no snapshot of every
     * loaded entity, no flush before the transaction ends) and tells the driver the transaction will
     * not write, which on a read replica is the difference between working and failing. On a
     * catalogue read that loads a page of 20 entities, skipping the snapshot is measurable.
     */
    /**
     * The hot path, and the one worth caching.
     *
     * <p>{@code @Cacheable} is cache-aside written as an annotation: Spring checks Redis, and only
     * calls this method on a miss. The annotation is on the SERVICE, not the controller and not the
     * repository - the controller would cache HTTP concerns (headers, status) and the repository
     * would cache entities, which are managed objects with lazy associations and a persistence
     * context behind them. A DTO is the right thing to put in a cache: immutable, self-contained,
     * and serialisable without dragging Hibernate into Redis.
     */
    @Override
    @Cacheable(cacheNames = CacheConfig.PRODUCT_BY_ID, key = "#id")
    @Transactional(readOnly = true)
    public ProductResponse getById(Long id) {
        return products.findById(id)
                .map(ProductResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("No product with id " + id));
    }

    @Override
    @Cacheable(cacheNames = CacheConfig.PRODUCT_BY_KEY, key = "#productKey")
    @Transactional(readOnly = true)
    public ProductResponse getByKey(String productKey) {
        return products.findByProductKey(productKey)
                .map(ProductResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("No product with key " + productKey));
    }

    /**
     * Bulk lookup. Returns what it finds; missing keys are simply absent.
     *
     * <p>Not an error, deliberately. The caller (order-service, pricing a cart) needs to know which
     * of its lines are unresolvable, and a 404 for the whole request would tell it only that
     * <em>something</em> was wrong. Returning the resolvable subset lets it name the bad lines.
     */
    @Override
    @Transactional(readOnly = true)
    public List<ProductResponse> getByKeys(List<String> productKeys) {
        if (productKeys == null || productKeys.isEmpty()) {
            return List.of();
        }
        return products.findByProductKeyIn(productKeys).stream().map(ProductResponse::from).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ProductResponse> browse(String category, BigDecimal maxPrice, Pageable pageable) {
        return PageResponse.of(products.browse(category, maxPrice, pageable).map(ProductResponse::from));
    }

    @Override
    @Cacheable(cacheNames = CacheConfig.CATEGORIES, key = "'all'")
    @Transactional(readOnly = true)
    public List<String> categories() {
        return products.findDistinctCategories();
    }

    @Override
    @CacheEvict(cacheNames = CacheConfig.CATEGORIES, allEntries = true)
    @Transactional
    public ProductResponse create(ProductRequest request) {
        Product saved = products.save(Product.builder()
                .productKey(request.productKey())
                .name(request.name())
                .category(request.category())
                .price(request.price())
                .weightGrams(request.weightGrams())
                .lengthCm(request.lengthCm())
                .heightCm(request.heightCm())
                .widthCm(request.widthCm())
                .photosQty(request.photosQty())
                .active(true)
                .build());
        announce(saved);
        log.info("Created product {} ({})", saved.getId(), saved.getProductKey());
        return ProductResponse.from(saved);
    }

    /**
     * Update in place, inside the transaction.
     *
     * <p>No explicit {@code save()}. The entity is managed, so Hibernate's dirty check writes the
     * changes at flush — and the {@code @Version} column is what turns a concurrent edit into an
     * {@code OptimisticLockingFailureException} rather than a silent lost update. Calling
     * {@code save()} here would work and would obscure that: it looks like the write happens because
     * of the call, and it does not.
     */
    /**
     * INVALIDATION, and note that it evicts from BOTH caches.
     *
     * <p>One product lives under two keys - its database id and its Olist key - because two
     * different callers ask two different questions. Evicting only the one the write happened to use
     * leaves the other serving a stale price for the whole TTL, and it is the single most common
     * cache-invalidation bug: the cache you remembered is not the cache the next reader uses.
     *
     * <p>{@code allEntries = true} on the key cache rather than a computed key, because the request
     * does not carry the product key and looking it up to build one would mean a database read
     * inside an eviction. Clearing 33K entries on a catalogue write is acceptable precisely because
     * catalogue writes are rare - which is the same property that made this table worth caching.
     */
    @Override
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConfig.PRODUCT_BY_ID, key = "#id"),
            @CacheEvict(cacheNames = CacheConfig.PRODUCT_BY_KEY, allEntries = true)
    })
    @Transactional
    public ProductResponse update(Long id, ProductRequest request) {
        Product product = products.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("No product with id " + id));

        product.setName(request.name());
        product.setCategory(request.category());
        product.setPrice(request.price());
        product.setWeightGrams(request.weightGrams());
        product.setLengthCm(request.lengthCm());
        product.setHeightCm(request.heightCm());
        product.setWidthCm(request.widthCm());
        product.setPhotosQty(request.photosQty());

        announce(product);
        log.info("Updated product {} ({})", id, product.getProductKey());
        return ProductResponse.from(product);
    }

    /**
     * A soft delete, and the reason is downstream rather than sentimental.
     *
     * <p>Order history refers to products by key, and the warehouse's {@code dim_product} has to
     * resolve every key that appears in any fact row — including products that are no longer
     * sellable. A hard delete would make historical orders unjoinable and would leave the star
     * schema with orphan facts, which is the classic way a warehouse loses a year of revenue for one
     * discontinued line.
     */
    @Override
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConfig.PRODUCT_BY_ID, key = "#id"),
            @CacheEvict(cacheNames = CacheConfig.PRODUCT_BY_KEY, allEntries = true),
            // The category list can change: deactivating the last product in a category removes it
            // from the vocabulary.
            @CacheEvict(cacheNames = CacheConfig.CATEGORIES, allEntries = true)
    })
    @Transactional
    public void delete(Long id) {
        Product product = products.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("No product with id " + id));
        product.setActive(false);
        // Announced like any other change. Search re-indexes it with active=false rather than
        // dropping the document - see ProductIndexer for why a read model keeps withdrawn products.
        announce(product);
        log.info("Deactivated product {} ({})", id, product.getProductKey());
    }

    /**
     * Re-announce every product, so a downstream read model can be rebuilt.
     *
     * <p>THROUGH THE SAME EVENT PATH, deliberately. The alternative - a script that reads
     * catalog-db and writes straight into Elasticsearch - would be faster and would create a second
     * way for a product to reach the index, which is a second thing to keep correct. When the
     * backfill path and the live path differ, the bug is always in the one nobody runs.
     *
     * <p>Needed at all because the Olist load goes in through SQL rather than the REST API (D14), so
     * 32,951 products exist that no event ever described.
     *
     * <p>Paged and flushed in batches: 32,951 outbox rows in one transaction is a transaction that
     * holds locks for the duration and a heap that holds every entity at once.
     */
    @Override
    @Transactional
    public long reindexAll(int batchSize) {
        long announced = 0;
        int page = 0;
        org.springframework.data.domain.Page<Product> slice;
        do {
            slice = products.findAll(
                    org.springframework.data.domain.PageRequest.of(page, batchSize));
            for (Product p : slice.getContent()) {
                announce(p);
                announced++;
            }
            page++;
        } while (slice.hasNext());

        log.info("Queued {} ProductUpdated events for reindexing", announced);
        return announced;
    }
}
