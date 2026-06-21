package com.scaler.typeahead.dto;

import java.util.List;

/**
 * Response body for {@code GET /suggest}.
 *
 * @param prefix      the normalized prefix that was queried
 * @param mode        ranking mode applied: {@code POPULARITY} or {@code RECENCY}
 * @param suggestions up to N prefix-matching suggestions, ordered by the mode's score
 * @param source      where the data came from: {@code CACHE}, {@code DB}, {@code TRIE},
 *                    {@code DB+RECENCY} or {@code EMPTY}
 * @param cacheNode   the consistent-hashing node that owns this prefix key
 * @param tookMs      server-side latency in milliseconds (for the perf report)
 */
public record SuggestResponse(
        String prefix,
        String mode,
        List<SuggestionDto> suggestions,
        String source,
        String cacheNode,
        long tookMs
) {
}
