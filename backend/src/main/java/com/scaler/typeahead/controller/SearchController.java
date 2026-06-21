package com.scaler.typeahead.controller;

import com.scaler.typeahead.dto.SearchRequest;
import com.scaler.typeahead.dto.SearchResponse;
import com.scaler.typeahead.service.SearchService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /search} - submit a search.
 *
 * <p>Returns {@code {"message":"Searched","query":...,"accepted":true}} and
 * records the query for count updates via the batch-write pipeline (FR 4.2).
 */
@RestController
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @PostMapping("/search")
    public SearchResponse search(@Valid @RequestBody SearchRequest request) {
        return searchService.submit(request.query());
    }
}
