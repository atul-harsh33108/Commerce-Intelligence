package com.ecommerce.catalog.controller;

import com.ecommerce.catalog.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The category list, which is a separate resource because it is a separate <em>thing</em>.
 *
 * <p>It could have been {@code GET /api/products/categories}. It is not, because that path says
 * "categories are a sub-resource of products", and they are not — they are the axis products are
 * filtered along. The distinction matters the moment a category acquires its own attributes.
 *
 * <p>In Olist the 71 categories are a genuine closed vocabulary from the source data, not a
 * free-text field, which is what makes them usable as search facets in Step 7.
 */
@RestController
@RequestMapping("/api/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final ProductService products;

    @GetMapping
    public List<String> categories() {
        return products.categories();
    }
}
