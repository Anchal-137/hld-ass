package com.scaler.typeahead.dto;

import java.util.List;

/**
 * Response body for {@code GET /trending}.
 *
 * @param mode        {@code POPULARITY} (version 1) or {@code RECENCY} (version 2)
 * @param suggestions trending queries ordered by the selected ranking
 * @param source      {@code CACHE} or {@code COMPUTED}
 * @param tookMs      server-side latency in milliseconds
 */
public record TrendingResponse(
        String mode,
        List<SuggestionDto> suggestions,
        String source,
        long tookMs
) {
}
