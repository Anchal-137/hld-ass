package com.scaler.typeahead.controller;

import com.scaler.typeahead.dto.SuggestResponse;
import com.scaler.typeahead.service.SuggestionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /suggest?q=<prefix>} - the core typeahead endpoint.
 *
 * <p>Returns up to N prefix-matching suggestions sorted by score desc. Empty /
 * missing input is handled gracefully (returns an empty list, never an error),
 * satisfying FR 4.1.
 */
@RestController
public class SuggestController {

    private final SuggestionService suggestionService;

    public SuggestController(SuggestionService suggestionService) {
        this.suggestionService = suggestionService;
    }

    @GetMapping("/suggest")
    public SuggestResponse suggest(
            @RequestParam(name = "q", required = false, defaultValue = "") String q,
            @RequestParam(name = "mode", required = false, defaultValue = "popularity") String mode) {
        return suggestionService.suggest(q, mode);
    }
}
