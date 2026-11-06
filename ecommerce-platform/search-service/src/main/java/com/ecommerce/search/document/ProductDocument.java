package com.ecommerce.search.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.MultiField;
import org.springframework.data.elasticsearch.annotations.InnerField;
import org.springframework.data.elasticsearch.annotations.Setting;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A product as Elasticsearch stores it.
 *
 * <h2>This is a READ MODEL, and it owns nothing</h2>
 *
 * <p>Every field here is a copy of something catalog-service owns. The index can be deleted at any
 * moment and rebuilt from the catalogue with no data loss — which is the property that makes it safe
 * to treat search as eventually consistent, and the reason there is a {@code /reindex} endpoint
 * rather than a backup.
 *
 * <p>The consequence people miss: <strong>search must never be the source of a write.</strong>
 * Nothing in this service updates a price. If it did, the next reindex would silently revert it.
 *
 * <h2>Why the mapping is not just "text everywhere"</h2>
 *
 * <p>Elasticsearch's two string types do opposite jobs and the distinction is the whole reason this
 * service exists rather than a {@code LIKE '%...%'} in Postgres:
 *
 * <ul>
 *   <li><strong>{@code text}</strong> is analysed — lowercased, split into terms, stemmed. It is
 *       what makes "beauty" match "health_beauty" and what fuzzy matching operates on. It cannot be
 *       aggregated or sorted usefully.</li>
 *   <li><strong>{@code keyword}</strong> is stored verbatim, one term. It is what facet counts and
 *       exact filters need, and it matches nothing unless the query is character-identical.</li>
 * </ul>
 *
 * <p>{@code category} needs <em>both</em>: a user types "beleza" and expects a match (text), and the
 * facet sidebar needs an exact count per category (keyword). {@code @MultiField} indexes it twice
 * under one name — {@code category} for searching, {@code category.keyword} for aggregating. Getting
 * this wrong is the single most common first-Elasticsearch mistake, and it fails asymmetrically: the
 * search works and the facets come back empty, or vice versa.
 */
@Document(indexName = "products")
@Setting(settingPath = "elasticsearch/product-settings.json")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductDocument {

    /**
     * The Olist product key, used as the Elasticsearch {@code _id}.
     *
     * <p>Deliberately the natural key rather than catalog-service's numeric id. Indexing is an
     * upsert on {@code _id}, so using a key the producer already knows makes a redelivered
     * {@code ProductUpdated} overwrite the same document instead of creating a second one — the same
     * at-least-once reasoning as everywhere else on this platform, solved by choosing the id well
     * rather than by adding a guard.
     */
    @Id
    private String productKey;

    /**
     * The database id, carried so a search result can link straight to {@code /api/products/{id}}
     * without a second lookup.
     */
    @Field(type = FieldType.Long)
    private Long productId;

    /**
     * The display name.
     *
     * <p>Worth remembering what this is: Olist has no product names, so it is derived from the
     * category (see docs/data-setup.md). Searching it is therefore close to searching the category
     * — which is why {@code searchText} exists rather than relying on this field alone.
     */
    @Field(type = FieldType.Text, analyzer = "product_analyzer")
    private String name;

    /** English category. Text for matching, {@code category.keyword} for facets. */
    @MultiField(
            mainField = @Field(type = FieldType.Text, analyzer = "product_analyzer"),
            otherFields = @InnerField(suffix = "keyword", type = FieldType.Keyword))
    private String category;

    /**
     * The Portuguese category from the source data.
     *
     * <p>Indexed so that a Brazilian user searching "beleza saude" finds the same products as one
     * searching "health beauty". That is not a nicety — it is the actual vocabulary of the source
     * dataset, and dropping it would make half the real category names unsearchable.
     */
    @MultiField(
            mainField = @Field(type = FieldType.Text, analyzer = "product_analyzer"),
            otherFields = @InnerField(suffix = "keyword", type = FieldType.Keyword))
    private String categoryPt;

    /**
     * Everything searchable, concatenated at index time.
     *
     * <p>A denormalised copy of name + both category spellings. Elasticsearch can search across
     * several fields with {@code multi_match} and this field is still worth having: it is one field
     * to fuzz, one set of term statistics, and one place to add a synonym later. The cost is index
     * size, which for 33K short strings is irrelevant.
     */
    @Field(type = FieldType.Text, analyzer = "product_analyzer")
    private String searchText;

    /**
     * {@code scaled_float} with a factor of 100, not {@code double}.
     *
     * <p>Money in binary floating point drifts; {@code scaled_float} stores it as a long of cents and
     * presents it as a decimal, which is exactly right for a price and sorts and range-filters
     * correctly. It is the one place Elasticsearch offers the same guarantee `NUMERIC(12,2)` gives
     * in Postgres.
     */
    @Field(type = FieldType.Scaled_Float, scalingFactor = 100)
    private BigDecimal price;

    @Field(type = FieldType.Integer)
    private Integer weightGrams;

    @Field(type = FieldType.Integer)
    private Integer photosQty;

    /**
     * Whether the product is currently for sale.
     *
     * <p>Indexed rather than filtered out at write time, because "show me discontinued products" is
     * a legitimate admin question and an index that has thrown the rows away cannot answer it. The
     * public search filters on it; the index keeps everything.
     */
    @Field(type = FieldType.Boolean)
    private boolean active;

    /**
     * When this document was written, from the event's own timestamp.
     *
     * <p>The basis of the freshness measurement in docs/measurements.md, and the thing to look at
     * when somebody says search is stale: comparing it to the catalogue's {@code updated_at} says
     * whether the event was slow or never arrived at all.
     */
    @Field(type = FieldType.Date, format = {}, pattern = "uuuu-MM-dd'T'HH:mm:ss.SSSXXX")
    private Instant indexedAt;
}
