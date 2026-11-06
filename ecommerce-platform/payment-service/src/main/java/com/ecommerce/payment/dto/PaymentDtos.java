package com.ecommerce.payment.dto;

import com.ecommerce.payment.entity.Payment;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

public final class PaymentDtos {

    private PaymentDtos() {}

    public record PaymentRequest(
            @NotBlank String orderKey,
            /*
             * Part of the idempotency key, and deliberately not generated per call. A caller that
             * sent a fresh value on each retry would defeat deduplication entirely - which is why
             * the key is derived from (orderKey, sequential) rather than supplied whole.
             */
            @Min(1) int sequential,
            @NotNull Payment.Method method,
            @Min(1) int installments,
            @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal amount
    ) {}

    public record PaymentResponse(
            Long id, String orderKey, int sequential, String method, int installments,
            BigDecimal amount, String status, String failureReason, Instant createdAt
    ) {
        public static PaymentResponse from(Payment p) {
            return new PaymentResponse(p.getId(), p.getOrderKey(), p.getPaymentSequential(),
                    p.getMethod().name(), p.getInstallments(), p.getAmount(),
                    p.getStatus().name(), p.getFailureReason(), p.getCreatedAt());
        }
    }
}
