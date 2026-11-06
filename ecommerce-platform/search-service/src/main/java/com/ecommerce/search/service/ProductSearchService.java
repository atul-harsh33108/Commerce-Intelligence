package com.ecommerce.search.service;

import co.elastic.clients.elasticsearch._types.query_dsl.Operator;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregation;
import com.ecommerce.search.document.ProductDocument;
import com.ecommerce.search.dto.SearchDtos.Facet;
import com.ecommerce.search.dto.SearchDtos.SearchHit;
import com.ecommerce.search.dto.SearchDtos.SearchResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.ElasticsearchAggregations;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Searching the product index.
 *
 * <h2>Why this is not {@code WHERE name LIKE '%beauty%'}</h2>
 *
 * <p>The question Step 7 has to answer is what a search engine buys over the database that already
 * holds the data. Three things, and only the third is about speed:
 *
 * <ol>
 *   <li><strong>Typo tolerance.</strong> `LIKE '%beaty%'` matches nothing. Fuzzy matching finds
 *       "beauty" at an edit distance of one, and that is the difference between a search box that
 *       works and one users abandon.</li>
 *   <li><strong>Relevance ranking.</strong> SQL returns a set; there is no notion of a better match.
 *       Elasticsearch scores every hit, so an exact category match outranks a partial name match
 *       without anyone writing that rule.</li>
 *   <li><strong>Facets in the same round trip.</strong> "23 results, and here is the count per
 *       category" is one query here and a second `GROUP BY` over the same predicate in SQL — which
 *       has to be kept in sync with the first by hand, forever.</li>
 * </ol>
 *
 * <p>A leading-wildcard `LIKE` also cannot use a B-tree index, so it is a full scan of 33K rows per
 * keystroke. That is the speed argument, and it is the least interesting of the four.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProductSearchService {

    private final ElasticsearchOperations elasticsearch;

    /**
     * Full-text search with optional facet filters.
     *
     * <h2>The query shape, and why each clause is there</h2>
     *
     * <p><strong>{@code multi_match} of type {@code BEST_FIELDS}</strong> across four fields with
     * different boosts. Best-fields scores a document by its single best-matching field rather than
     * summing them, which is right here: a product matching "beauty" strongly in {@code category} is
     * a better hit than one matching weakly in three fields at once. {@code MOST_FIELDS} would
     * reward the latter.
     *
     * <p><strong>Boosts</strong> encode what a match in each field is worth. {@code category^3}
     * because — with Olist having no real product names — the category is the only genuinely
     * meaningful text; {@code name^2}; the Portuguese category and the concatenated blob at 1.
     *
     * <p><strong>{@code fuzziness AUTO}</strong> is the typo tolerance, and AUTO rather than a fixed
     * edit distance for a specific reason: it scales with term length. One edit on a three-letter
     * word turns it into a different word ("cat"→"car"), so AUTO allows none below 3 characters, one
     * up to 5, and two beyond. A flat {@code fuzziness: 2} makes short queries match nearly
     * everything.
     *
     * <p><strong>{@code prefixLength 1}</strong> — the first character must be right. This is purely
     * a cost control: fuzzy matching expands the term against the index, and anchoring the first
     * character cuts that expansion dramatically. It also matches how people typo, which is rarely
     * on the first letter.
     *
     * <p><strong>{@code operator OR}</strong>, deliberately. With AND, "health beauty gift" returns
     * nothing unless a product matches all three. OR plus relevance ranking gives the best matches
     * first and still returns something useful — which is what a search box should do.
     *
     * <p>The category and price filters go in a {@code filter} clause rather than {@code must},
     * because a filter contributes no score and is cacheable by Elasticsearch. A facet selection is
     * a yes/no constraint, not evidence of relevance.
     */
    public SearchResponse search(String query, String category, BigDecimal maxPrice,
                                 boolean includeInactive, int page, int size) {

        List<Query> filters = new ArrayList<>();
        if (category != null && !category.isBlank()) {
            // `.keyword`, not `category` - a facet filter has to be exact. Filtering on the analysed
            // field would make "health_beauty" also match "health" and quietly widen the facet.
            filters.add(Query.of(q -> q.term(t -> t.field("category.keyword").value(category))));
        }
        if (maxPrice != null) {
            filters.add(Query.of(q -> q.range(r -> r.untyped(u -> u
                    .field("price").lte(co.elastic.clients.json.JsonData.of(maxPrice))))));
        }
        if (!includeInactive) {
            filters.add(Query.of(q -> q.term(t -> t.field("active").value(true))));
        }

        Query text = (query == null || query.isBlank())
                // An empty query is a browse, not a search. match_all keeps the facets and filters
                // meaningful instead of returning nothing.
                ? Query.of(q -> q.matchAll(m -> m))
                : Query.of(q -> q.multiMatch(m -> m
                        .query(query)
                        .fields("category^3", "name^2", "categoryPt", "searchText")
                        .type(TextQueryType.BestFields)
                        .fuzziness("AUTO")
                        .prefixLength(1)
                        .operator(Operator.Or)));

        Query full = Query.of(q -> q.bool(b -> b.must(text).filter(filters)));

        NativeQuery search = NativeQuery.builder()
                .withQuery(full)
                // The facet counts come back in the SAME round trip as the hits. That is the whole
                // argument for aggregations over a second GROUP BY: one query means the counts
                // cannot disagree with the results they describe.
                .withAggregation("categories", Aggregation.of(a -> a
                        .terms(t -> t.field("category.keyword").size(25))))
                .withPageable(PageRequest.of(page, size))
                /*
                 * COUNT THE HITS EXACTLY, and this line is a bug fix rather than a preference.
                 *
                 * Elasticsearch stops counting at 10,000 by default and reports `gte 10000` from
                 * there on, because for a web search "about 10,000 results" is as useful as an exact
                 * figure and cheaper to produce - it lets the collector skip whole segments once it
                 * knows nothing else can make the top N.
                 *
                 * On a 32,951-document index that default is not a rounding, it is wrong. The
                 * storefront showed "10,000 matches - page 1 of 834" for an empty query, against a
                 * catalogue three times that size, and every query broad enough to exceed 10,000 hits
                 * lost its last two thirds of pagination. It was invisible in the Step 7 numbers
                 * because every query I had measured (`health beauty`, 2,444; `computr acessorie`,
                 * 2,867) returned fewer than 10,000 hits. Browsing with no query at all is what
                 * exposed it - the one case I had not measured because it looked too simple to break.
                 *
                 * Exact counting costs on an index of tens of millions. This index is 33K.
                 */
                .withTrackTotalHits(true)
                .build();

        SearchHits<ProductDocument> hits = elasticsearch.search(search, ProductDocument.class);

        List<SearchHit> results = hits.getSearchHits().stream()
                .map(h -> SearchHit.from(h.getContent(), h.getScore()))
                .toList();

        return new SearchResponse(query, results, facetsOf(hits),
                hits.getTotalHits(), page, size,
                (int) Math.ceil(hits.getTotalHits() / (double) size));
    }

    /** Pulls the terms aggregation out of the response, or an empty list if it is absent. */
    private List<Facet> facetsOf(SearchHits<ProductDocument> hits) {
        if (!(hits.getAggregations() instanceof ElasticsearchAggregations aggregations)) {
            return List.of();
        }
        var categories = aggregations.get("categories");
        if (categories == null) {
            return List.of();
        }
        return categories.aggregation().getAggregate().sterms().buckets().array().stream()
                .map(b -> new Facet(b.key().stringValue(), b.docCount()))
                .toList();
    }

    /**
     * "Did you mean" style suggestions: the distinct categories matching a partial term.
     *
     * <p>Implemented as a terms aggregation over matched documents rather than a
     * {@code completion} suggester, because the vocabulary here is 71 categories rather than a
     * million product titles. A suggester would be the right tool at the other scale and is
     * needless machinery at this one.
     */
    public List<String> suggestCategories(String partial, int limit) {
        Query q = (partial == null || partial.isBlank())
                ? Query.of(b -> b.matchAll(m -> m))
                : Query.of(b -> b.multiMatch(m -> m
                        .query(partial)
                        .fields("category", "categoryPt")
                        .fuzziness("AUTO")
                        .prefixLength(1)));

        NativeQuery search = NativeQuery.builder()
                .withQuery(q)
                .withAggregation("categories", Aggregation.of(a -> a
                        .terms(t -> t.field("category.keyword").size(limit))))
                .withMaxResults(0)   // aggregations only; the documents are not wanted
                .build();

        return facetsOf(elasticsearch.search(search, ProductDocument.class)).stream()
                .map(Facet::value).toList();
    }

    public long documentCount() {
        return elasticsearch.count(
                NativeQuery.builder().withQuery(q -> q.matchAll(m -> m)).build(),
                ProductDocument.class);
    }
}
