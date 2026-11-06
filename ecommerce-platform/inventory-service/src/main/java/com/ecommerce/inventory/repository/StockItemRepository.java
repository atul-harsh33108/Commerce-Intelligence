package com.ecommerce.inventory.repository;

import com.ecommerce.inventory.entity.StockItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * Top-level, not nested inside a holder class.
 *
 * <p>These three repositories were briefly written as nested interfaces in one file, because three
 * four-line interfaces in three files is mostly ceremony. Spring Data's repository scanner does not
 * pick nested interfaces up, and the failure is a startup error naming the inner class
 * ({@code Repositories$StockItemRepository}) as a missing bean - which reads as a component-scan
 * package problem rather than a nesting one. Recorded here so the next attempt at tidying does not
 * repeat it.
 */
public interface StockItemRepository extends JpaRepository<StockItem, Long> {

    Optional<StockItem> findByProductKey(String productKey);

    List<StockItem> findByProductKeyIn(List<String> productKeys);

    /**
     * Total units currently spoken for.
     *
     * <p>An aggregate query rather than {@code findAll().stream().mapToInt(...)}, because the second
     * pulls 33,000 entities into the heap to add up one column.
     */
    @Query("SELECT coalesce(sum(s.quantityReserved), 0) FROM StockItem s")
    long totalReserved();
}
