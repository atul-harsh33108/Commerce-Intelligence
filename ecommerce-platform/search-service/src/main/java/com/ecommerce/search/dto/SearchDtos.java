package com.ecommerce.search.dto;

import com.ecommerce.search.document.ProductDocument;

import java.math.BigDecimal;
import java.util.List;

public final class SearchDtos {

    private SearchDtos() {}

    /**
     * One hit, with its relevance score.
     *
     * <p>The score is exposed deliberately. It is the one number that explains why the results are
     * in this order, and hiding it turns "why is this product third?" into an unanswerable question.
     * It is NOT comparable between queries - Elasticsearch scores are relative to one search - and
     * saying so here is cheaper than explaining it later.
     */
    public record SearchHit(
            String productKey,
            Long productId,
            String name,
            String category,
            String categoryPt,
            BigDecimal price,
            boolean active,
            float score
    ) {
        public static SearchHit from(ProductDocument d, float score) {
            return new SearchHit(d.getProductKey(), d.getProductId(), d.getName(), d.getCategory(),
                    d.getCategoryPt(), d.getPrice(), d.isActive(), score);
        }
    }

    /** One facet bucket: a category and how many of the CURRENT results fall in it. */
    public record Facet(String value, long count) {}

    public record SearchResponse(
            String query,
            List<SearchHit> results,
            List<Facet> categories,
            long totalHits,
            int page,
            int size,
            int totalPages
    ) {}

    public record IndexStatus(long documents, String index) {}
}
