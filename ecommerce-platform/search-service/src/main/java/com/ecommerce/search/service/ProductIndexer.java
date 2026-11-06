package com.ecommerce.search.service;

import com.ecommerce.search.document.ProductDocument;
import com.ecommerce.search.event.Events;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.stream.Stream;

/**
 * Turns a ProductUpdated event into an indexed document.
 *
 * <h2>Indexing is an upsert, by construction</h2>
 *
 * <p>Elasticsearch indexes by {@code _id}, and the id here is the product key the producer already
 * knows. So a redelivered event overwrites the same document rather than creating a second one -
 * at-least-once handled by choosing the identifier well, with no guard table and no deduplication
 * code. It is the cheapest idempotency on the platform and the only one that needed no thought
 * beyond picking the right key.
 *
 * <h2>What a DELETE means to a read model</h2>
 *
 * <p>catalog-service never hard-deletes; it sets {@code active = false} (see its ProductServiceImpl,
 * and the reason - order history and dim_product both have to resolve the key forever). So this
 * service never removes a document either. It re-indexes with {@code active = false}, and the public
 * search filters those out while an admin query can still find them.
 *
 * <p>Deleting the document instead would work for the shop and would make "which products did we
 * withdraw?" unanswerable - a question the index is otherwise perfectly placed to answer.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProductIndexer {

    private final ElasticsearchOperations elasticsearch;

    public void index(Events.ProductUpdated event) {
        elasticsearch.save(toDocument(event));
        log.debug("Indexed {} ({})", event.productKey(), event.category());
    }

    /**
     * The searchable blob, built at index time rather than at query time.
     *
     * <p>Underscores are left in place: the index analyser has a char filter that turns them into
     * spaces, so `health_beauty` is tokenised as `health` and `beauty` and a user typing either word
     * matches. Stripping them here as well would be the same work done twice, in the place where it
     * cannot be changed without a reindex.
     */
    private ProductDocument toDocument(Events.ProductUpdated e) {
        String searchText = Stream.of(e.name(), e.category(), e.categoryPt())
                .filter(s -> s != null && !s.isBlank())
                .reduce("", (a, b) -> a.isEmpty() ? b : a + " " + b);

        return ProductDocument.builder()
                .productKey(e.productKey())
                .productId(e.productId())
                .name(e.name())
                .category(e.category())
                .categoryPt(e.categoryPt())
                .searchText(searchText)
                .price(e.price())
                .weightGrams(e.weightGrams())
                .photosQty(e.photosQty())
                .active(e.active())
                .indexedAt(Instant.now())
                .build();
    }
}
