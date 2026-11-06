package com.ecommerce.catalog.service;

import com.ecommerce.catalog.dto.ProductDtos.PageResponse;
import com.ecommerce.catalog.dto.ProductDtos.ProductRequest;
import com.ecommerce.catalog.dto.ProductDtos.ProductResponse;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;

public interface ProductService {

    ProductResponse getById(Long id);

    ProductResponse getByKey(String productKey);

    List<ProductResponse> getByKeys(List<String> productKeys);

    PageResponse<ProductResponse> browse(String category, BigDecimal maxPrice, Pageable pageable);

    List<String> categories();

    ProductResponse create(ProductRequest request);

    ProductResponse update(Long id, ProductRequest request);

    void delete(Long id);

    /** Re-announce every product so a read model can be rebuilt. Returns how many were queued. */
    long reindexAll(int batchSize);
}
