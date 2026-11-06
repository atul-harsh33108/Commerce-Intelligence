package com.ecommerce.order.controller;

import com.ecommerce.order.dto.OrderDtos.OrderResponse;
import com.ecommerce.order.dto.OrderDtos.PageResponse;
import com.ecommerce.order.dto.OrderDtos.PlaceOrderRequest;
import com.ecommerce.order.security.AuthenticatedUser;
import com.ecommerce.order.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orders;

    @PostMapping
    public ResponseEntity<OrderResponse> place(@Valid @RequestBody PlaceOrderRequest request,
                                               @AuthenticationPrincipal AuthenticatedUser caller) {
        OrderResponse created = orders.place(request, caller);
        return ResponseEntity.created(URI.create("/api/orders/key/" + created.orderKey()))
                .body(created);
    }

    @GetMapping("/{id}")
    public OrderResponse byId(@PathVariable Long id,
                              @AuthenticationPrincipal AuthenticatedUser caller) {
        return orders.byId(id, caller);
    }

    @GetMapping("/key/{orderKey}")
    public OrderResponse byKey(@PathVariable String orderKey,
                               @AuthenticationPrincipal AuthenticatedUser caller) {
        return orders.byKey(orderKey, caller);
    }

    @GetMapping("/me")
    public PageResponse<OrderResponse> mine(@AuthenticationPrincipal AuthenticatedUser caller,
                                            @PageableDefault(size = 20) Pageable pageable) {
        return orders.mine(caller, pageable);
    }

    /**
     * Confirm an order as paid.
     *
     * <p>ADMIN-only in {@code SecurityConfig}, because in the synchronous flow the caller is
     * payment-service acting on a successful capture, not a customer. That is a genuine limitation
     * of Step 3 and is one of the reasons Step 5 moves it onto an event: a service-to-service call
     * that has to borrow an ADMIN identity is a call that should not be an HTTP request at all.
     */
    @PostMapping("/key/{orderKey}/confirm")
    public OrderResponse confirm(@PathVariable String orderKey) {
        return orders.markPaid(orderKey);
    }

    @PostMapping("/key/{orderKey}/cancel")
    public OrderResponse cancel(@PathVariable String orderKey,
                                @RequestParam(defaultValue = "Cancelled by request") String reason) {
        return orders.cancel(orderKey, reason);
    }
}
