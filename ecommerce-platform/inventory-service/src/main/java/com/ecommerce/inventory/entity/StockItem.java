package com.ecommerce.inventory.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * The stock counter for one product.
 *
 * <h2>Available is derived, never stored</h2>
 *
 * <p>{@code available = onHand - reserved}. Storing it as a third column would create a value that
 * can disagree with the two it comes from, and a disagreement in an inventory system means either
 * overselling or unsellable stock — both expensive, both silent.
 *
 * <h2>Why {@code @Version} and not a pessimistic lock</h2>
 *
 * <p>This is the one hot row on the platform: the same popular product is reserved by many orders at
 * once. That is the case where the usual advice ("optimistic for low contention, pessimistic for
 * high") points at {@code SELECT ... FOR UPDATE}.
 *
 * <p>Optimistic is used anyway, with a retry, for a reason specific to this shape: the transaction
 * is short (read the row, check, write) and the conflict rate even under load is a few per cent. A
 * row lock serialises <em>every</em> reservation for that product including the 95% that would not
 * have conflicted, and it holds the lock across the transaction's other work. The version check
 * fails only the ones that actually raced, and the retry re-reads and succeeds. What makes this safe
 * rather than merely faster is that the database's own {@code CHECK} constraints are the backstop —
 * see V1__init.sql. If the conflict rate ever rose enough for retries to thrash, that is when
 * pessimistic locking becomes the right answer, and it is a one-line change here.
 */
@Entity
@Table(name = "stock_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class StockItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_key", nullable = false, unique = true, length = 64)
    private String productKey;

    @Column(name = "quantity_on_hand", nullable = false)
    private int quantityOnHand;

    @Column(name = "quantity_reserved", nullable = false)
    @Builder.Default
    private int quantityReserved = 0;

    @Version
    private Long version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Transient
    public int getAvailable() {
        return quantityOnHand - quantityReserved;
    }

    /** Reserve, or say why not. Never partially. */
    public void reserve(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Reservation quantity must be positive");
        }
        if (getAvailable() < quantity) {
            throw new InsufficientStockException(productKey, quantity, getAvailable());
        }
        quantityReserved += quantity;
    }

    /** Compensation: the saga rolled back and the stock goes back on the shelf. */
    public void release(int quantity) {
        quantityReserved = Math.max(0, quantityReserved - quantity);
    }

    /**
     * The order was paid: the reservation becomes a real removal.
     *
     * <p>Both counters move together — on-hand down, reserved down — because the goods have left.
     * Decrementing only on-hand would leave the reservation standing against stock that is gone.
     */
    public void confirm(int quantity) {
        quantityOnHand = Math.max(0, quantityOnHand - quantity);
        quantityReserved = Math.max(0, quantityReserved - quantity);
    }
}
