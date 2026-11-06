package com.ecommerce.catalog.repository;

import com.ecommerce.catalog.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long> {

    Optional<Product> findByProductKey(String productKey);

    List<Product> findByProductKeyIn(List<String> productKeys);

    /**
     * Browse, with two optional filters.
     *
     * <p>An explicit {@code @Query} rather than a derived method name, and the reason is the
     * {@code :category IS NULL} pattern: a derived name cannot express "filter only if the caller
     * supplied one", so the alternative is either a `Specification` (more machinery than two
     * predicates deserve) or four methods and a chain of ifs in the service.
     *
     * <p><strong>The type cast is not decoration.</strong> Postgres cannot infer the type of a
     * parameter that appears only in {@code IS NULL} comparisons, and without help it reports
     * "could not determine data type of parameter $1" at execution — a runtime failure with a
     * message that never mentions the query. Hibernate 6 emits the cast when the parameter is typed,
     * which it is here because the method signature says {@code String}.
     */
    @Query("""
            SELECT p FROM Product p
             WHERE p.active = true
               AND (:category IS NULL OR p.category = :category)
               AND (:maxPrice IS NULL OR p.price <= :maxPrice)
            """)
    Page<Product> browse(@Param("category") String category,
                         @Param("maxPrice") java.math.BigDecimal maxPrice,
                         Pageable pageable);

    /**
     * The distinct category list for the browse sidebar.
     *
     * <p>A projection, not entities. Returning {@code List<Product>} and mapping in Java would pull
     * ~32K rows out of the database to produce 71 strings.
     */
    @Query("SELECT DISTINCT p.category FROM Product p WHERE p.active = true AND p.category IS NOT NULL ORDER BY p.category")
    List<String> findDistinctCategories();

    long countByActiveTrue();
}
