package com.ecommerce.search.controller;

import com.ecommerce.search.dto.SearchDtos.IndexStatus;
import com.ecommerce.search.dto.SearchDtos.SearchResponse;
import com.ecommerce.search.service.ProductSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final ProductSearchService search;

    /**
     * Full-text search with facets.
     *
     * <p>GET with query parameters rather than POST with a body, because a search IS a resource
     * identified by its parameters: the URL is shareable, bookmarkable and cacheable, and the back
     * button works. A POST search breaks all four to gain a nested JSON body this API does not need.
     *
     * <p>`size` is capped. An uncapped page size on an index is a denial-of-service vector wearing a
     * pagination parameter, and Elasticsearch's own `max_result_window` would refuse it anyway - one
     * error the caller can act on beats another they cannot.
     */
    @GetMapping
    public SearchResponse search(
            @RequestParam(required = false, name = "q") String query,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) BigDecimal maxPrice,
            @RequestParam(defaultValue = "false") boolean includeInactive,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        return search.search(query, category, maxPrice, includeInactive,
                Math.max(page, 0), Math.min(Math.max(size, 1), 100));
    }

    /** Category suggestions for a partial term - the "did you mean" behind a search box. */
    @GetMapping("/suggest")
    public List<String> suggest(@RequestParam(name = "q", required = false) String partial,
                                @RequestParam(defaultValue = "10") int limit) {
        return search.suggestCategories(partial, Math.min(Math.max(limit, 1), 50));
    }

    /**
     * How many documents the index holds.
     *
     * <p>Public, and the reason is operational rather than generous: comparing it with the
     * catalogue's product count is how anybody establishes whether search is missing anything, and a
     * health signal nobody can reach is a health signal nobody uses.
     */
    @GetMapping("/status")
    public IndexStatus status() {
        return new IndexStatus(search.documentCount(), "products");
    }
}
