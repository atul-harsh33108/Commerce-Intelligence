package com.ecommerce.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One line of an order.
 *
 * <h2>The price is copied, not looked up</h2>
 *
 * <p>{@code unitPrice} is a snapshot taken from the catalogue at the moment the order was placed,
 * and it never changes afterwards. That is not denormalisation for speed — it is correctness. An
 * order is a contract at a price, and a receipt that re-reads the current catalogue price would
 * rewrite history every time somebody edits the catalogue. The same reasoning is why
 * {@code product_key} is stored rather than a foreign key to a product row that lives in another
 * service's database.
 */
@Entity
@Table(name = "order_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    /** 1-based within the order. Olist's {@code order_item_id}. */
    @Column(name = "line_no", nullable = false)
    private Integer lineNo;

    @Column(name = "product_key", nullable = false, length = 64)
    private String productKey;

    @Column(name = "seller_key", length = 64)
    private String sellerKey;

    @Column(nullable = false)
    @Builder.Default
    private Integer quantity = 1;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "freight_amount", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal freightAmount = BigDecimal.ZERO;

    @Column(name = "shipping_limit_at")
    private Instant shippingLimitAt;

    /**
     * {@code (unitPrice * quantity) + freight}.
     *
     * <p>{@code BigDecimal} throughout, never {@code double}. Binary floating point cannot represent
     * 0.10, so a total assembled from doubles drifts by cents — invisible in a demo, a reconciliation
     * failure in a ledger.
     */
    public BigDecimal lineTotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity)).add(freightAmount);
    }
}
