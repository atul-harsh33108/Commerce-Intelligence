package com.ecommerce.inventory.controller;

import com.ecommerce.inventory.dto.InventoryDtos.ReserveRequest;
import com.ecommerce.inventory.dto.InventoryDtos.ReserveResponse;
import com.ecommerce.inventory.dto.InventoryDtos.StockResponse;
import com.ecommerce.inventory.service.InventoryService;
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

/**
 * HTTP for stock.
 *
 * <p>The write endpoints exist for two callers and it is worth naming them, because the saga makes
 * them look redundant:
 *
 * <ul>
 *   <li><strong>Step 3's synchronous order flow</strong> uses {@code POST /reserve} directly, which
 *       is what makes the seam visible before Kafka hides it.</li>
 *   <li><strong>Tests and operators</strong> need a way to drive this service without standing up a
 *       broker.</li>
 * </ul>
 *
 * <p>From Step 5 the production path is the Kafka listener, and these endpoints stay — an
 * event-driven service with no way to be exercised synchronously is a service that can only be
 * debugged by publishing to a topic.
 */
@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventory;

    @GetMapping("/{productKey}")
    public StockResponse byKey(@PathVariable String productKey) {
        return inventory.byProductKey(productKey);
    }

    @GetMapping
    public List<StockResponse> byKeys(@RequestParam List<String> productKeys) {
        return inventory.byProductKeys(productKeys);
    }

    @PostMapping("/reserve")
    public ReserveResponse reserve(@Valid @RequestBody ReserveRequest request) {
        var created = inventory.reserve(request.orderKey(), request.lines());
        return new ReserveResponse(request.orderKey(), created.size());
    }

    /**
     * Compensation, reachable by whoever could reserve.
     *
     * <p>A failed order that only a human with database access could unwind is not a compensating
     * action, it is a support ticket.
     */
    @PostMapping("/release/{orderKey}")
    public ReserveResponse release(@PathVariable String orderKey) {
        return new ReserveResponse(orderKey, inventory.release(orderKey));
    }

    @PostMapping("/confirm/{orderKey}")
    public ReserveResponse confirm(@PathVariable String orderKey) {
        return new ReserveResponse(orderKey, inventory.confirm(orderKey));
    }
}
