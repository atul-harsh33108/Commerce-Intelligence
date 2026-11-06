package com.ecommerce.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "payments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class Payment {

    public enum Status { CAPTURED, DECLINED, REFUNDED }

    /** Olist's payment_type vocabulary, uppercased. Real values from real transactions. */
    public enum Method { CREDIT_CARD, BOLETO, VOUCHER, DEBIT_CARD, NOT_DEFINED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_key", nullable = false, length = 64)
    private String orderKey;

    @Column(name = "payment_sequential", nullable = false)
    @Builder.Default
    private Integer paymentSequential = 1;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    @Builder.Default
    private Method method = Method.CREDIT_CARD;

    @Column(nullable = false)
    @Builder.Default
    private Integer installments = 1;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    /**
     * {@code "<orderKey>:<sequential>"}, computed rather than supplied.
     *
     * <p>See the column comment in V1__init.sql: a derived key cannot be forgotten by a caller and
     * cannot be regenerated fresh on a retry, which is what makes a redelivered payment request a
     * duplicate-key violation instead of a second charge.
     */
    @Column(name = "idempotency_key", nullable = false, unique = true, length = 96)
    private String idempotencyKey;

    @Version
    private Long version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static String idempotencyKeyFor(String orderKey, int sequential) {
        return orderKey + ":" + sequential;
    }
}
