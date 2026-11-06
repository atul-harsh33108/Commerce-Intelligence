package com.ecommerce.order.dto;

import com.ecommerce.order.entity.Order;
import com.ecommerce.order.entity.OrderItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class OrderDtos {

    private OrderDtos() {}

    /**
     * What a client sends to place an order.
     *
     * <p><strong>No prices.</strong> The client names products and quantities; the server prices
     * them from the catalogue. A request that carried a price would be a discount endpoint — and it
     * is a mistake that is easy to make, because echoing the price the UI already displayed is the
     * shortest path.
     *
     * <p>No customer id either: that comes from the verified token.
     */
    public record PlaceOrderRequest(
            @NotEmpty(message = "an order needs at least one line")
            @Size(max = 50, message = "an order may have at most 50 lines")
            @Valid List<Line> lines
    ) {
        public record Line(
                @NotBlank String productKey,
                // Bounded on both sides. A zero or negative quantity is nonsense; an unbounded one is
                // a way to reserve the entire warehouse in one request.
                @Min(value = 1, message = "quantity must be at least 1")
                @Max(value = 100, message = "quantity may be at most 100 per line")
                int quantity
        ) {}
    }

    public record OrderItemResponse(
            Integer lineNo, String productKey, String sellerKey, int quantity,
            BigDecimal unitPrice, BigDecimal freightAmount, BigDecimal lineTotal
    ) {
        public static OrderItemResponse from(OrderItem i) {
            return new OrderItemResponse(i.getLineNo(), i.getProductKey(), i.getSellerKey(),
                    i.getQuantity(), i.getUnitPrice(), i.getFreightAmount(), i.lineTotal());
        }
    }

    public record OrderResponse(
            Long id,
            String orderKey,
            String customerKey,
            Long userId,
            String status,
            String failureReason,
            BigDecimal totalAmount,
            Instant purchasedAt,
            Instant estimatedDeliveryAt,
            Instant deliveredCustomerAt,
            List<OrderItemResponse> items
    ) {
        /** With items. Only safe on a query that JOIN FETCHed them - see OrderRepository. */
        public static OrderResponse withItems(Order o) {
            return new OrderResponse(o.getId(), o.getOrderKey(), o.getCustomerKey(), o.getUserId(),
                    o.getStatus().name(), o.getFailureReason(), o.getTotalAmount(),
                    o.getPurchasedAt(), o.getEstimatedDeliveryAt(), o.getDeliveredCustomerAt(),
                    o.getItems().stream().map(OrderItemResponse::from).toList());
        }

        /**
         * Without items, for list views.
         *
         * <p>A separate factory rather than a null check inside one, because the difference is about
         * which query produced the entity. Calling {@code withItems} on a lazily-loaded order outside
         * a transaction throws {@code LazyInitializationException}; having two methods makes the
         * caller state which one it has.
         */
        public static OrderResponse summary(Order o) {
            return new OrderResponse(o.getId(), o.getOrderKey(), o.getCustomerKey(), o.getUserId(),
                    o.getStatus().name(), o.getFailureReason(), o.getTotalAmount(),
                    o.getPurchasedAt(), o.getEstimatedDeliveryAt(), o.getDeliveredCustomerAt(),
                    List.of());
        }
    }

    public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
        public static <T> PageResponse<T> of(org.springframework.data.domain.Page<T> page) {
            return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                    page.getTotalElements(), page.getTotalPages());
        }
    }
}
