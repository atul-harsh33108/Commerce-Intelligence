package com.ecommerce.payment.controller;

import com.ecommerce.payment.dto.PaymentDtos.PaymentRequest;
import com.ecommerce.payment.dto.PaymentDtos.PaymentResponse;
import com.ecommerce.payment.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService payments;

    /**
     * POST is not idempotent in HTTP's definition, and this endpoint is idempotent anyway.
     *
     * <p>Not a contradiction: HTTP describes what a client may ASSUME, and a client may not assume a
     * POST is safe to repeat. This service makes it safe regardless, keyed on
     * (orderKey, sequential) - which is the only workable arrangement when the caller may be a Kafka
     * consumer replaying an event rather than a browser.
     */
    @PostMapping
    public PaymentResponse pay(@Valid @RequestBody PaymentRequest request) {
        return payments.pay(request);
    }

    @GetMapping("/order/{orderKey}")
    public List<PaymentResponse> byOrder(@PathVariable String orderKey) {
        return payments.byOrderKey(orderKey);
    }

    @PostMapping("/order/{orderKey}/refund")
    public PaymentResponse refund(@PathVariable String orderKey,
                                  @RequestParam(defaultValue = "Saga compensation") String reason) {
        return payments.refund(orderKey, reason);
    }
}
