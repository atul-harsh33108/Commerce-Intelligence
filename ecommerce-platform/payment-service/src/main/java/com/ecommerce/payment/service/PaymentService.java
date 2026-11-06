package com.ecommerce.payment.service;

import com.ecommerce.payment.config.PaymentProperties;
import com.ecommerce.payment.dto.PaymentDtos.PaymentRequest;
import com.ecommerce.payment.dto.PaymentDtos.PaymentResponse;
import com.ecommerce.payment.entity.Payment;
import com.ecommerce.payment.exception.ResourceNotFoundException;
import com.ecommerce.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Charging a customer, exactly once.
 *
 * <h2>Idempotency, done twice, on purpose</h2>
 *
 * <p>Two layers, and the second is the one that actually holds:
 *
 * <ol>
 *   <li><strong>A lookup</strong> — if a payment with this idempotency key already exists, return
 *       it. Fast, and produces a sensible answer.</li>
 *   <li><strong>A unique constraint</strong> — because the lookup is a check-then-act race. Two
 *       concurrent redeliveries of the same event both find nothing and both insert; one wins, the
 *       other gets a constraint violation, and that violation is caught and turned into the same
 *       "already done" answer. <em>The database is what makes it correct; the lookup only makes it
 *       cheap.</em></li>
 * </ol>
 *
 * <p>Getting this wrong is the single most expensive bug in an event-driven commerce system, and it
 * is entirely invisible in testing: at-least-once delivery means duplicates arrive rarely, under
 * load, on the paths nobody watches.
 *
 * <h2>The gateway is simulated, and says so</h2>
 *
 * <p>There is no real payment provider here. {@code app.payment.failure-rate} decides the outcome,
 * defaulting to 0 so the happy path is deterministic. The saga's compensation test sets it to 1.0 —
 * which is the point: a compensating transaction that has never been observed to run is a comment,
 * not a feature.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {

    private final PaymentRepository payments;
    private final PaymentProperties properties;

    @Transactional
    public PaymentResponse pay(PaymentRequest request) {
        String key = Payment.idempotencyKeyFor(request.orderKey(), request.sequential());

        var existing = payments.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            log.info("Payment for {} already processed ({}); returning the original result",
                    key, existing.get().getStatus());
            return PaymentResponse.from(existing.get());
        }

        boolean declined = ThreadLocalRandom.current().nextDouble() < properties.failureRate();
        sleepFor(properties.latency());

        Payment payment = Payment.builder()
                .orderKey(request.orderKey())
                .paymentSequential(request.sequential())
                .method(request.method())
                .installments(request.installments())
                .amount(request.amount())
                .status(declined ? Payment.Status.DECLINED : Payment.Status.CAPTURED)
                .failureReason(declined ? "Declined by the simulated payment gateway" : null)
                .idempotencyKey(key)
                .build();

        try {
            payments.save(payment);
            payments.flush();   // force the constraint check here, not at commit
        } catch (DataIntegrityViolationException race) {
            // The check-then-act race actually happened: another thread inserted the same key
            // between the lookup and this insert. The winner's row is the answer.
            log.info("Concurrent duplicate for {}; returning the winning row", key);
            return payments.findByIdempotencyKey(key)
                    .map(PaymentResponse::from)
                    .orElseThrow(() -> race);
        }

        log.info("Payment {} for order {}: {} {}", key, request.orderKey(),
                payment.getStatus(), payment.getAmount());
        return PaymentResponse.from(payment);
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> byOrderKey(String orderKey) {
        List<Payment> found = payments.findByOrderKeyOrderByPaymentSequential(orderKey);
        if (found.isEmpty()) {
            throw new ResourceNotFoundException("No payments for order " + orderKey);
        }
        return found.stream().map(PaymentResponse::from).toList();
    }

    /**
     * Compensation on the money side.
     *
     * <p>A refund is a NEW row, never an update to the captured one. An accounting record that is
     * edited in place loses the fact that money moved twice, and "what did we actually charge this
     * customer" stops being answerable from the table. Financial state is append-only for the same
     * reason a ledger is.
     */
    @Transactional
    public PaymentResponse refund(String orderKey, String reason) {
        List<Payment> captured = payments.findByOrderKeyOrderByPaymentSequential(orderKey).stream()
                .filter(p -> p.getStatus() == Payment.Status.CAPTURED)
                .toList();

        if (captured.isEmpty()) {
            throw new ResourceNotFoundException("No captured payment to refund for order " + orderKey);
        }

        Payment original = captured.get(0);
        String key = Payment.idempotencyKeyFor(orderKey, -original.getPaymentSequential());

        var alreadyRefunded = payments.findByIdempotencyKey(key);
        if (alreadyRefunded.isPresent()) {
            return PaymentResponse.from(alreadyRefunded.get());
        }

        Payment refund = Payment.builder()
                .orderKey(orderKey)
                .paymentSequential(-original.getPaymentSequential())
                .method(original.getMethod())
                .installments(1)
                .amount(original.getAmount())
                .status(Payment.Status.REFUNDED)
                .failureReason(reason)
                .idempotencyKey(key)
                .build();

        payments.save(refund);
        log.info("Refunded {} for order {}: {}", original.getAmount(), orderKey, reason);
        return PaymentResponse.from(refund);
    }

    private static void sleepFor(java.time.Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return;
        }
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException ex) {
            // Restore the flag rather than swallowing it: something asked this thread to stop, and a
            // shutdown that hangs because an interrupt was eaten is a genuinely hard bug to find.
            Thread.currentThread().interrupt();
        }
    }
}
