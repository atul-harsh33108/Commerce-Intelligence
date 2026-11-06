package com.ecommerce.order.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * An order: the platform's unit of business, and the saga's subject.
 */
@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_key", nullable = false, unique = true, length = 64)
    private String orderKey;

    @Column(name = "customer_key", length = 64)
    private String customerKey;

    @Column(name = "user_id")
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    @Builder.Default
    private OrderStatus status = OrderStatus.PENDING;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    @Builder.Default
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "purchased_at")
    private Instant purchasedAt;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "delivered_carrier_at")
    private Instant deliveredCarrierAt;

    @Column(name = "delivered_customer_at")
    private Instant deliveredCustomerAt;

    @Column(name = "estimated_delivery_at")
    private Instant estimatedDeliveryAt;

    // --- Saga timestamps (V2__outbox.sql) --------------------------------------------------------
    //
    // Not a separate `saga_state` table. The saga's state IS the order's status, and duplicating it
    // into a second table creates two answers to "what happened to this order" that can disagree.
    // What the status alone could not say is WHEN each step completed, which is what lets the
    // recovery job distinguish "waiting" from "stuck".

    /** When inventory confirmed the reservation. Null for an order that never got that far. */
    @Column(name = "reserved_at")
    private Instant reservedAt;

    /** When payment reported a successful capture. */
    @Column(name = "paid_at")
    private Instant paidAt;

    /** When the saga compensated. Non-null implies CANCELED with a `failureReason`. */
    @Column(name = "compensated_at")
    private Instant compensatedAt;

    /**
     * {@code LAZY}, and fetched explicitly by the one query that needs them.
     *
     * <p>{@code EAGER} on a collection is the N+1 generator: every {@code findAll} silently issues
     * one extra query per order, and the list endpoint over 100K orders becomes 100K queries. The
     * repository has a {@code JOIN FETCH} variant for the single-order read, which is the only place
     * the items are wanted.
     *
     * <p>{@code orphanRemoval} because an item removed from this list has no meaning on its own —
     * unlike, say, a cancelled payment, which is a record worth keeping.
     */
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    @Builder.Default
    private List<OrderItem> items = new ArrayList<>();

    @Version
    private Long version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Adds an item and keeps both sides of the association consistent.
     *
     * <p>Setting only one side is the classic JPA bug: the in-memory object graph looks right, the
     * insert writes a null foreign key, and the failure appears at flush with a message about a
     * not-null constraint on a column nobody set.
     */
    public void addItem(OrderItem item) {
        items.add(item);
        item.setOrder(item.getOrder() == null ? this : item.getOrder());
        item.setOrder(this);
    }

    /** The order's own arithmetic, so no caller can disagree with it. */
    public void recalculateTotal() {
        this.totalAmount = items.stream()
                .map(OrderItem::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
