package com.ecommerce.catalog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One sellable product.
 *
 * <h2>Two identifiers, and the distinction matters</h2>
 *
 * <p>{@code id} is a surrogate key: a bigserial that means nothing outside this database.
 * {@code productKey} is the natural key — the 32-character hash Olist uses — and it is what every
 * other system, every CSV row and every re-run of the loader agrees on.
 *
 * <p>Both exist because each is bad at the other's job. A natural key as the primary key means a
 * 32-character string in every foreign key and every index; a surrogate key alone means the loader
 * has no way to answer "have I already imported this row?" and re-running it duplicates the catalog.
 * The unique constraint on {@code productKey} is what makes the loader idempotent (D14).
 *
 * <h2>What Olist does and does not contain</h2>
 *
 * <p><strong>Olist products have no names and no prices.</strong> The dataset gives a category, four
 * physical measurements, a photo count, and the character lengths of the (absent) name and
 * description. That is a real property of real anonymised commercial data and it is not papered over
 * here:
 *
 * <ul>
 *   <li>{@code name} is <em>derived</em> — "{@code <english category> #<first 8 of product_key>}" —
 *       and the derivation lives in the loader, in one place, so it is reproducible rather than
 *       invented per row. It exists so the UI has something to render.</li>
 *   <li>{@code price} is <em>observed</em>, not fabricated: the median unit price this product
 *       actually sold at across all Olist order items. A product that never sold has no price and is
 *       marked inactive, which is honest and is also why the active-product count is lower than the
 *       row count.</li>
 * </ul>
 *
 * <p>The searchable text is therefore the <em>category</em>, which is genuine data — 71 real
 * categories across ~32K products — and is what Step 7's typo tolerance is demonstrated against.
 *
 * <h2>Optimistic locking</h2>
 *
 * <p>{@code @Version} rather than a pessimistic lock. Catalog writes are rare and almost never
 * conflict, so taking a row lock on every update pays a cost on the common path to solve a problem
 * that occurs on the rare one. A version column makes the conflict a 409 for the second writer,
 * which is the correct answer to "you edited a row somebody else changed while you were looking at
 * it".
 */
@Entity
@Table(name = "products")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@jakarta.persistence.EntityListeners(AuditingEntityListener.class)
public class Product implements Serializable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The Olist {@code product_id}. Immutable once set; the loader deduplicates on it. */
    @Column(name = "product_key", nullable = false, unique = true, length = 64)
    private String productKey;

    /** Derived for display — see the class comment. Never treated as authoritative. */
    @Column(nullable = false, length = 200)
    private String name;

    /** The Portuguese category as it appears in the source data, kept so the load is reversible. */
    @Column(name = "category_key", length = 100)
    private String categoryKey;

    /** The English translation from {@code product_category_name_translation.csv}. */
    @Column(name = "category", length = 100)
    private String category;

    /**
     * The median unit price observed in {@code olist_order_items_dataset.csv}, in BRL.
     *
     * <p>{@code numeric(12,2)} in the database and {@code BigDecimal} here, never {@code double}.
     * Binary floating point cannot represent 0.10, so a total assembled from doubles drifts by cents
     * — which is invisible in a demo and a reconciliation failure in a real ledger.
     */
    @Column(precision = 12, scale = 2)
    private BigDecimal price;

    @Column(name = "weight_g")
    private Integer weightGrams;

    @Column(name = "length_cm")
    private Integer lengthCm;

    @Column(name = "height_cm")
    private Integer heightCm;

    @Column(name = "width_cm")
    private Integer widthCm;

    @Column(name = "photos_qty")
    private Integer photosQty;

    /**
     * False for a product that never sold and therefore has no observed price.
     *
     * <p>Deliberately a flag rather than a deletion. The row is still needed: order history refers
     * to it, and the warehouse's {@code dim_product} has to be able to resolve every product key
     * that ever appears in a fact row, including ones that are no longer sellable.
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Version
    private Long version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
