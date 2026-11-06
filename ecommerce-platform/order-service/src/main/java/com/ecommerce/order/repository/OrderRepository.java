package com.ecommerce.order.repository;

import com.ecommerce.order.entity.Order;
import com.ecommerce.order.entity.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /**
     * One order with its items, in one query.
     *
     * <p>{@code LEFT JOIN FETCH}, not the default lazy load: reading an order and then rendering its
     * lines is two queries with lazy fetching, and with {@code open-in-view: false} (which is the
     * correct setting) the second one throws instead, outside the transaction. This is the pattern
     * that makes "LAZY everywhere" workable — lazy by default, fetched explicitly where needed.
     *
     * <p>LEFT rather than INNER because an order with no items is a data problem worth seeing, not a
     * row to hide.
     */
    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.items WHERE o.id = :id")
    Optional<Order> findByIdWithItems(@Param("id") Long id);

    @Query("SELECT o FROM Order o LEFT JOIN FETCH o.items WHERE o.orderKey = :orderKey")
    Optional<Order> findByOrderKeyWithItems(@Param("orderKey") String orderKey);

    Optional<Order> findByOrderKey(String orderKey);

    /**
     * "My orders."
     *
     * <p>Deliberately NOT join-fetched. A page of 20 orders with their items is a cartesian product
     * that breaks pagination — Hibernate cannot apply a LIMIT to a joined result without pulling
     * everything into memory first, and it warns about exactly that
     * ({@code HHH90003004: firstResult/maxResults specified with collection fetch}). The list view
     * shows totals and statuses, not lines.
     */
    Page<Order> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Page<Order> findByCustomerKeyOrderByCreatedAtDesc(String customerKey, Pageable pageable);

    /**
     * Orders whose saga has been in flight too long.
     *
     * <p>The recovery job's only query, and the reason for the partial index in V1__init.sql: in a
     * table where 99.99% of rows are terminal, this asks about the handful that are not.
     */
    @Query("""
            SELECT o FROM Order o
             WHERE o.status IN :statuses
               AND o.createdAt < :before
            """)
    List<Order> findStuck(@Param("statuses") List<OrderStatus> statuses,
                          @Param("before") Instant before);

    long countByStatus(OrderStatus status);
}
