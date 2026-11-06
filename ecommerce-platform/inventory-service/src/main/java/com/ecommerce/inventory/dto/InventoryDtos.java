package com.ecommerce.inventory.dto;

import com.ecommerce.inventory.entity.StockItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public final class InventoryDtos {

    private InventoryDtos() {}

    public record StockResponse(String productKey, int onHand, int reserved, int available) {
        public static StockResponse from(StockItem s) {
            return new StockResponse(s.getProductKey(), s.getQuantityOnHand(),
                    s.getQuantityReserved(), s.getAvailable());
        }
    }

    public record ReserveLine(@NotBlank String productKey, @Min(1) int quantity) {}

    public record ReserveRequest(
            @NotBlank String orderKey,
            @NotEmpty @Valid List<ReserveLine> lines
    ) {}

    public record ReserveResponse(String orderKey, int linesReserved) {}
}
